package org.example.tika;

import org.mozilla.universalchardet.UniversalDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TextFileReader {
    private static final Logger logger = LoggerFactory.getLogger(TextFileReader.class);
    private static final int DETECT_BUFFER_SIZE = 4096;

    private TextFileReader() {
    }

    public static String read(Path path, int maxStringLength) throws IOException {
        CharsetDecision decision = detectCharset(path);
        Charset charset = decision.charset();

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), charset))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (content.length() + read > maxStringLength) {
                    int remaining = Math.max(0, maxStringLength - content.length());
                    if (remaining > 0) {
                        content.append(buffer, 0, remaining);
                    }
                    logger.info("Текст из {} был усечен до {} символов.", path, maxStringLength);
                    break;
                }
                content.append(buffer, 0, read);
            }
        }

        if (decision.fallbackUsed()) {
            logger.info("Чтение {} выполнено с fallback-кодировкой {}.", path, charset.name());
        } else {
            logger.info("Чтение {} выполнено с кодировкой {}.", path, charset.name());
        }
        return content.toString();
    }

    private static CharsetDecision detectCharset(Path path) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            input.mark(4);
            byte[] bom = new byte[4];
            int read = input.read(bom);
            input.reset();
            if (read >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
                return new CharsetDecision(StandardCharsets.UTF_8, false);
            }
            if (read >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
                return new CharsetDecision(StandardCharsets.UTF_16LE, false);
            }
            if (read >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
                return new CharsetDecision(StandardCharsets.UTF_16BE, false);
            }
        }

        String detected = detectWithUniversalChardet(path);
        if (detected != null) {
            try {
                return new CharsetDecision(Charset.forName(detected), false);
            } catch (Exception e) {
                logger.warn("Не удалось применить определенную кодировку {} для {}. Будет использован fallback.", detected, path);
            }
        }

        return new CharsetDecision(Charset.forName("windows-1251"), true);
    }

    private static String detectWithUniversalChardet(Path path) throws IOException {
        UniversalDetector detector = new UniversalDetector(null);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[DETECT_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) > 0 && !detector.isDone()) {
                detector.handleData(buffer, 0, read);
            }
        } finally {
            detector.dataEnd();
        }
        return detector.getDetectedCharset();
    }

    private record CharsetDecision(Charset charset, boolean fallbackUsed) {
    }
}
