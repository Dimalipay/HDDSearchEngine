package org.example.tika;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.RecursiveParserWrapper;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.RecursiveParserWrapperHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.xml.sax.SAXException;

public class TikaService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TikaService.class);

    private final int     maxStringLength;
    private final int     timeoutSeconds;
    private final boolean ocrEnabled;
    private final String  ocrLanguage;
    private final ExecutorService parserExecutor;

    // ── Конструкторы ──────────────────────────────────────────────────────────

    public TikaService(int maxStringLength, int timeoutSeconds) {
        this(maxStringLength, timeoutSeconds, false, "rus+eng");
    }

    public TikaService(int maxStringLength, int timeoutSeconds,
                       boolean ocrEnabled, String ocrLanguage) {
        this.maxStringLength = maxStringLength;
        this.timeoutSeconds  = timeoutSeconds;
        this.ocrEnabled      = ocrEnabled;
        this.ocrLanguage     = ocrLanguage != null ? ocrLanguage : "rus+eng";
        this.parserExecutor  = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("tika-parser-" + t.getId());
            return t;
        });
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    public String parseToString(Path path) throws Exception {
        Future<String> future = parserExecutor.submit(() -> {
            if (isTextFile(path)) {
                return TextFileReader.read(path, maxStringLength);
            }
            // Email-файлы и PST/OST — рекурсивный парсинг с извлечением вложений
            if (isEmailFile(path)) {
                return parseEmailRecursive(path);
            }
            if (ocrEnabled && (isImageFile(path) || isPdfFile(path))) {
                return parseWithOcr(path);
            }
            return parseWithTika(path);
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("Превышен таймаут обработки {} ({} сек.)", path, timeoutSeconds);
            throw e;
        }
    }

    // ── Tesseract ─────────────────────────────────────────────────────────────

    public static boolean isTesseractAvailable() {
        String bundledDir = resolveTesseractPath();
        if (bundledDir != null) {
            Path exe = Paths.get(bundledDir, "tesseract.exe");
            if (Files.exists(exe)) {
                injectIntoPath(bundledDir);
                logger.info("Tesseract найден (бандл): {}", exe);
                return true;
            }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("tesseract", "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            if (finished && proc.exitValue() == 0) {
                logger.info("Tesseract найден в системном PATH.");
                return true;
            }
        } catch (Exception e) {
            logger.debug("Tesseract не найден в системном PATH: {}", e.getMessage());
        }
        logger.warn("Tesseract не найден ни рядом с приложением, ни в системном PATH.");
        return false;
    }

    public static String resolveTesseractPath() {
        try {
            Path jarLocation = Paths.get(
                    TikaService.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            );
            Path appDir = jarLocation.getParent();
            if (appDir != null && "app".equalsIgnoreCase(appDir.getFileName().toString())) {
                appDir = appDir.getParent();
            }
            if (appDir == null) return null;
            Path bundledExe = appDir.resolve("tesseract").resolve("tesseract.exe");
            if (Files.exists(bundledExe)) {
                return bundledExe.getParent().toString();
            }
        } catch (Exception e) {
            logger.debug("Не удалось определить путь к приложению: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void injectIntoPath(String directory) {
        try {
            Class<?> processEnvClass = Class.forName("java.lang.ProcessEnvironment");
            Field theEnvironmentField = processEnvClass.getDeclaredField("theEnvironment");
            theEnvironmentField.setAccessible(true);
            Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
            String currentPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
            String separator = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";
            if (!currentPath.contains(directory)) {
                env.put("PATH", directory + separator + currentPath);
                env.put("Path", directory + separator + currentPath);
                logger.info("Tesseract добавлен в PATH процесса: {}", directory);
            }
        } catch (Exception e) {
            logger.warn("Не удалось добавить Tesseract в PATH через рефлексию: {}. " +
                    "Убедитесь что Tesseract установлен в системном PATH.", e.getMessage());
        }
    }

    @Override
    public void close() {
        parserExecutor.shutdownNow();
    }

    // ── Приватные методы ──────────────────────────────────────────────────────

    /**
     * Рекурсивный парсинг email-файлов: .eml, .msg, .pst, .ost
     *
     * RecursiveParserWrapper обходит письмо и ВСЕ его вложения (PDF, DOCX, изображения),
     * извлекая текст из каждого. Результаты склеиваются в одну строку.
     *
     * Для PST/OST требуется зависимость com.pff:java-libpst в build.gradle.
     */
    private String parseEmailRecursive(Path path) throws Exception {
        AutoDetectParser baseParser = new AutoDetectParser();
        RecursiveParserWrapper wrapper = new RecursiveParserWrapper(baseParser);

        ParseContext context = new ParseContext();
        context.set(Parser.class, baseParser);

        // Если OCR включён — добавляем конфигурацию Tesseract для изображений-вложений
        if (ocrEnabled) {
            TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
            ocrConfig.setLanguage(ocrLanguage);
            context.set(TesseractOCRConfig.class, ocrConfig);
        }

        ContentHandlerFactory factory = new BasicContentHandlerFactory(
                BasicContentHandlerFactory.HANDLER_TYPE.TEXT,
                maxStringLength > 0 ? maxStringLength : -1
        );

        RecursiveParserWrapperHandler handler = new RecursiveParserWrapperHandler(
                factory,
                -1  // -1 = без ограничения глубины вложений
        );

        Metadata metadata = new Metadata();

        try (InputStream stream = Files.newInputStream(path)) {
            wrapper.parse(stream, handler, metadata, context);
        }

        // Собираем текст из письма + всех вложений
        List<Metadata> metadataList = handler.getMetadataList();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < metadataList.size(); i++) {
            Metadata m = metadataList.get(i);
            String content = m.get(TikaCoreProperties.TIKA_CONTENT);
            if (content != null && !content.isBlank()) {
                sb.append(content).append("\n");
            }
        }

        String result = sb.toString().trim();
        String ext = getExtension(path);

        if (result.isBlank()) {
            logger.warn("Email {} — текст не извлечён. " +
                            "Для .pst/.ost проверьте наличие зависимости java-libpst в build.gradle.",
                    path.getFileName());
        } else {
            logger.info("Email {} [.{}] — извлечено {} символов из {} частей (письмо + вложения).",
                    path.getFileName(), ext, result.length(), metadataList.size());
        }

        return result;
    }

    private String parseWithTika(Path path) throws Exception {
        Tika tika = new Tika();
        tika.setMaxStringLength(maxStringLength);
        String content = tika.parseToString(path);
        if (content != null && content.length() >= maxStringLength) {
            logger.info("Текст из {} усечён до {} символов.", path, maxStringLength);
        }
        if (isPdfFile(path)) {
            int len = content == null ? 0 : content.length();
            logger.info("PDF {} извлечён: {} символов.", path, len);
            if (len == 0) {
                logger.warn("PDF {} — текст пустой. Это скан-PDF? Включите OCR.", path);
            }
        }
        return content;
    }

    private String parseWithOcr(Path path) throws Exception {
        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setLanguage(ocrLanguage);

        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        context.set(TesseractOCRConfig.class, ocrConfig);
        context.set(Parser.class, parser);

        Metadata metadata = new Metadata();
        BodyContentHandler handler = new BodyContentHandler(maxStringLength > 0 ? maxStringLength : -1);

        try (InputStream stream = Files.newInputStream(path)) {
            try {
                parser.parse(stream, handler, metadata, context);
            } catch (SAXException sax) {
                if (isWriteLimitReached(sax)) {
                    logger.warn("OCR {} превысил лимит {} символов. Текст усечён.",
                            path.getFileName(), maxStringLength);
                } else {
                    throw sax;
                }
            }
        }

        String result = handler.toString();
        if (result.isBlank()) {
            logger.warn("OCR не извлёк текст из {}. " +
                            "Проверьте языковые пакеты tessdata (rus.traineddata, eng.traineddata).",
                    path.getFileName());
        } else {
            logger.info("OCR {} -> {} символов (язык: {}).",
                    path.getFileName(), result.length(), ocrLanguage);
        }
        return result;
    }

    private boolean isWriteLimitReached(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getClass().getSimpleName().contains("WriteLimitReachedException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTextFile(Path p) {
        return getExtension(p).equals("txt");
    }

    private boolean isPdfFile(Path p) {
        return getExtension(p).equals("pdf");
    }

    private boolean isImageFile(Path p) {
        String ext = getExtension(p);
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("tiff") || ext.equals("tif") || ext.equals("bmp")
                || ext.equals("gif");
    }

    /**
     * Email-форматы, требующие рекурсивного парсинга.
     * .pst / .ost — весь почтовый ящик Outlook (требует java-libpst)
     * .msg         — одно письмо Outlook (сохранённое вручную)
     * .eml         — стандартный формат письма (RFC 822)
     */
    private boolean isEmailFile(Path p) {
        String ext = getExtension(p);
        return ext.equals("pst") || ext.equals("ost")
                || ext.equals("msg") || ext.equals("eml");
    }
}
