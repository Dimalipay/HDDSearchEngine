package org.example.tika;

import org.apache.tika.Tika;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.RecursiveParserWrapper;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ContentHandlerFactory;
import org.apache.tika.sax.RecursiveParserWrapperHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TikaService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TikaService.class);

    private static final Set<String> EMAIL_EXTENSIONS =
            Set.of("eml", "msg", "pst", "ost", "mbox");

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

    // ── Публичный API: извлечение текста ──────────────────────────────────────

    public String parseToString(Path path) throws Exception {
        Future<String> future = parserExecutor.submit(() -> {
            if (isTextFile(path))  return TextFileReader.read(path, maxStringLength);
            if (isEmailFile(path)) return parseEmailRecursive(path);
            if (ocrEnabled && (isImageFile(path) || isPdfFile(path))) return parseWithOcr(path);
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

    // ── Публичный API: извлечение вложений ────────────────────────────────────

    /**
     * Извлекает все вложения email-файла в папку {@code outputDir}.
     *
     * <p>Поддерживает все форматы через Tika {@link EmbeddedDocumentExtractor}:
     * .eml, .msg, .pst, .ost, .mbox — в отличие от Jakarta Mail, который понимает
     * только RFC 822 (.eml).</p>
     *
     * <p>Каждое вложение сохраняется под оригинальным именем.
     * При конфликте имён: {@code file.pdf} → {@code file_(1).pdf}, {@code file_(2).pdf}.</p>
     *
     * @param emailPath путь к файлу письма
     * @param outputDir папка назначения (создаётся автоматически)
     * @return количество успешно сохранённых вложений
     */
    public int extractAttachmentsToDir(Path emailPath, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);

        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context    = new ParseContext();
        context.set(Parser.class, parser);

        AtomicInteger count  = new AtomicInteger(0);
        Set<String> usedNames = new LinkedHashSet<>();   // для разрешения конфликтов

        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {

            @Override
            public boolean shouldParseEmbedded(Metadata meta) {
                // Пропускаем части без имени — это тело письма, а не вложение
                String name  = meta.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                String ctype = meta.get(Metadata.CONTENT_TYPE);
                return name != null && !name.isBlank()
                        && (ctype == null || !ctype.startsWith("message/"));
            }

            @Override
            public void parseEmbedded(InputStream stream,
                                      ContentHandler handler,
                                      Metadata meta,
                                      boolean outputHtml) throws IOException {
                String rawName = meta.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                if (rawName == null || rawName.isBlank()) {
                    rawName = "attachment_" + (count.get() + 1);
                }

                // Убираем символы запрещённые в Windows/Linux именах файлов
                String safeName = rawName
                        .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                        .strip();
                if (safeName.isBlank()) safeName = "attachment_" + (count.get() + 1);

                // Уникальное имя в рамках этой папки
                safeName = uniqueName(usedNames, safeName);
                usedNames.add(safeName);

                // Читаем весь поток: InputStream однопроходный, нельзя передавать дальше
                byte[] bytes = stream.readAllBytes();
                if (bytes.length == 0) return;  // пустое вложение — пропускаем

                Path dest = outputDir.resolve(safeName);
                Files.copy(new ByteArrayInputStream(bytes), dest,
                        StandardCopyOption.REPLACE_EXISTING);
                count.incrementAndGet();
                logger.debug("  вложение: {} ({} байт)", dest.getFileName(), bytes.length);
            }
        });

        Metadata rootMeta = new Metadata();
        try (InputStream in = Files.newInputStream(emailPath)) {
            // DefaultHandler — нас интересуют только байты вложений, не текст тела
            parser.parse(in, new DefaultHandler(), rootMeta, context);
        } catch (SAXException | org.apache.tika.exception.TikaException ex) {
            // Частичный сбой не критичен: вложения до ошибки уже сохранены
            logger.warn("Частичная ошибка разбора '{}' (вложения сохранены): {}",
                    emailPath.getFileName(), ex.getMessage());
        }

        int total = count.get();
        if (total > 0) {
            logger.info("Из '{}' извлечено {} вложений → '{}'",
                    emailPath.getFileName(), total, outputDir);
        } else {
            logger.info("В '{}' вложений не найдено", emailPath.getFileName());
        }
        return total;
    }

    // ── Статические утилиты ───────────────────────────────────────────────────

    /**
     * Возвращает {@code true} если расширение относится к email-формату.
     * Используется из ExportService без создания экземпляра TikaService.
     *
     * @param extension расширение без точки, любой регистр ("eml", "MSG", ...)
     */
    public static boolean isEmailExtension(String extension) {
        return extension != null
                && EMAIL_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
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
                    TikaService.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path appDir = jarLocation.getParent();
            if (appDir != null && "app".equalsIgnoreCase(appDir.getFileName().toString())) {
                appDir = appDir.getParent();
            }
            if (appDir == null) return null;
            Path bundledExe = appDir.resolve("tesseract").resolve("tesseract.exe");
            if (Files.exists(bundledExe)) return bundledExe.getParent().toString();
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
            String sep = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";
            if (!currentPath.contains(directory)) {
                env.put("PATH", directory + sep + currentPath);
                env.put("Path", directory + sep + currentPath);
                logger.info("Tesseract добавлен в PATH процесса: {}", directory);
            }
        } catch (Exception e) {
            logger.warn("Не удалось добавить Tesseract в PATH: {}. " +
                    "Убедитесь что Tesseract установлен в системном PATH.", e.getMessage());
        }
    }

    @Override
    public void close() {
        parserExecutor.shutdownNow();
    }

    // ── Приватные методы парсинга ─────────────────────────────────────────────

    /**
     * Рекурсивный парсинг email: RecursiveParserWrapper обходит письмо
     * и ВСЕ его вложения (PDF, DOCX, изображения), склеивает текст.
     * Для .pst/.ost требуется зависимость com.pff:java-libpst.
     */
    private String parseEmailRecursive(Path path) throws Exception {
        AutoDetectParser baseParser = new AutoDetectParser();
        RecursiveParserWrapper wrapper = new RecursiveParserWrapper(baseParser);

        ParseContext context = new ParseContext();
        context.set(Parser.class, baseParser);
        if (ocrEnabled) {
            TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
            ocrConfig.setLanguage(ocrLanguage);
            context.set(TesseractOCRConfig.class, ocrConfig);
        }

        ContentHandlerFactory factory = new BasicContentHandlerFactory(
                BasicContentHandlerFactory.HANDLER_TYPE.TEXT,
                maxStringLength > 0 ? maxStringLength : -1);
        RecursiveParserWrapperHandler handler =
                new RecursiveParserWrapperHandler(factory, -1);
        Metadata metadata = new Metadata();

        try (InputStream stream = Files.newInputStream(path)) {
            wrapper.parse(stream, handler, metadata, context);
        }

        List<Metadata> parts = handler.getMetadataList();
        StringBuilder sb = new StringBuilder();
        for (Metadata m : parts) {
            String content = m.get(TikaCoreProperties.TIKA_CONTENT);
            if (content != null && !content.isBlank()) sb.append(content).append("\n");
        }

        String result = sb.toString().trim();
        if (result.isBlank()) {
            logger.warn("Email '{}' — текст не извлечён. " +
                    "Для .pst/.ost проверьте зависимость java-libpst.", path.getFileName());
        } else {
            logger.info("Email '{}' [.{}] — {} символов из {} частей.",
                    path.getFileName(), getExtension(path), result.length(), parts.size());
        }
        return result;
    }

    private String parseWithTika(Path path) throws Exception {
        Tika tika = new Tika();
        tika.setMaxStringLength(maxStringLength);
        String content = tika.parseToString(path);
        if (content != null && content.length() >= maxStringLength) {
            logger.info("Текст из '{}' усечён до {} символов.", path, maxStringLength);
        }
        if (isPdfFile(path)) {
            int len = content == null ? 0 : content.length();
            if (len == 0) logger.warn("PDF '{}' — текст пустой. Это скан-PDF? Включите OCR.", path);
            else          logger.info("PDF '{}' — {} символов.", path, len);
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
        BodyContentHandler handler = new BodyContentHandler(
                maxStringLength > 0 ? maxStringLength : -1);

        try (InputStream stream = Files.newInputStream(path)) {
            try {
                parser.parse(stream, handler, metadata, context);
            } catch (SAXException sax) {
                if (isWriteLimitReached(sax)) {
                    logger.warn("OCR '{}' превысил лимит {} символов. Текст усечён.",
                            path.getFileName(), maxStringLength);
                } else throw sax;
            }
        }

        String result = handler.toString();
        if (result.isBlank()) {
            logger.warn("OCR не извлёк текст из '{}'. Проверьте tessdata.", path.getFileName());
        } else {
            logger.info("OCR '{}' → {} символов (язык: {}).",
                    path.getFileName(), result.length(), ocrLanguage);
        }
        return result;
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    /** Уникальное имя: если {@code name} уже в {@code used} — добавляет суффикс _(1), _(2)... */
    private static String uniqueName(Set<String> used, String name) {
        if (!used.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot)    : "";
        int i = 1;
        String candidate;
        do { candidate = base + "_(" + i++ + ")" + ext; }
        while (used.contains(candidate));
        return candidate;
    }

    private boolean isWriteLimitReached(Throwable t) {
        while (t != null) {
            if (t.getClass().getSimpleName().contains("WriteLimitReachedException")) return true;
            t = t.getCause();
        }
        return false;
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1)
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private boolean isTextFile(Path p)  { return "txt".equals(getExtension(p)); }
    private boolean isPdfFile(Path p)   { return "pdf".equals(getExtension(p)); }
    private boolean isEmailFile(Path p) { return isEmailExtension(getExtension(p)); }

    private boolean isImageFile(Path p) {
        String e = getExtension(p);
        return "jpg".equals(e) || "jpeg".equals(e) || "png".equals(e)
                || "tiff".equals(e) || "tif".equals(e) || "bmp".equals(e) || "gif".equals(e);
    }
}
