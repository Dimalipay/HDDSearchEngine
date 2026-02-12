package org.example.tika;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.*;

public class TikaService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TikaService.class);

    private final int maxStringLength;
    private final int timeoutSeconds;
    private final ExecutorService parserExecutor;

    public TikaService(int maxStringLength, int timeoutSeconds) {
        this.maxStringLength = maxStringLength;
        this.timeoutSeconds = timeoutSeconds;
        this.parserExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("tika-parser-" + thread.getId());
            return thread;
        });
    }

    public String parseToString(Path path) throws Exception {
        Future<String> future = parserExecutor.submit(() -> {
            if (isTextFile(path)) {
                return TextFileReader.read(path, maxStringLength);
            }
            Tika tika = new Tika();
            tika.setMaxStringLength(maxStringLength);
            String content = tika.parseToString(path);
            if (content != null && content.length() >= maxStringLength) {
                logger.info("Текст из {} был усечен до {} символов.", path, maxStringLength);
            }
            return content;
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("Превышен таймаут обработки {} ({} сек.)", path, timeoutSeconds);
            throw e;
        }
    }

    @Override
    public void close() {
        parserExecutor.shutdownNow();
    }

    private boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".txt");
    }
}
