package org.example.tika;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

public class TikaService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TikaService.class);

    private final int     maxStringLength;
    private final int     timeoutSeconds;
    private final boolean ocrEnabled;
    private final String  ocrLanguage;
    private final ExecutorService parserExecutor;

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

    public String parseToString(Path path) throws Exception {
        Future<String> future = parserExecutor.submit(() -> {
            if (isTextFile(path)) {
                return TextFileReader.read(path, maxStringLength);
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

    /**
     * Проверяет наличие Tesseract OCR в системе.
     */
    public static boolean isTesseractAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tesseract", "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            return finished && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        parserExecutor.shutdownNow();
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

    /**
     * Парсинг с OCR через AutoDetectParser + TesseractOCRConfig.
     * Для PDF: Tika сначала извлекает встроенный текст, страницы без него отдаёт Tesseract.
     * Для изображений: Tesseract вызывается напрямую.
     */
    private String parseWithOcr(Path path) throws Exception {
        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setLanguage(ocrLanguage);
        // Таймаут управляется через Future.get(timeoutSeconds) в parseToString()

        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        context.set(TesseractOCRConfig.class, ocrConfig);
        context.set(Parser.class, parser);

        Metadata metadata = new Metadata();
        BodyContentHandler handler = new BodyContentHandler(maxStringLength > 0 ? maxStringLength : -1);

        try (InputStream stream = Files.newInputStream(path)) {
            parser.parse(stream, handler, metadata, context);
        }

        String result = handler.toString();
        logger.info("OCR {} -> {} символов (язык: {}).", path.getFileName(), result.length(), ocrLanguage);
        return result;
    }

    private boolean isTextFile(Path p) {
        return p.getFileName().toString().toLowerCase().endsWith(".txt");
    }

    private boolean isPdfFile(Path p) {
        return p.getFileName().toString().toLowerCase().endsWith(".pdf");
    }

    private boolean isImageFile(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".tiff") || n.endsWith(".tif") || n.endsWith(".bmp")
                || n.endsWith(".gif");
    }
}
