package org.example.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExportService {
    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    public ExportResult exportToDirectory(List<Path> inputFiles,
                                          Path targetDirectory,
                                          ConflictStrategy conflictStrategy,
                                          BiConsumer<Integer, Integer> progressCallback) {
        Set<Path> files = sanitize(inputFiles);
        ensureDirectory(targetDirectory);

        Path commonRoot = commonRoot(files);
        int total = files.size();
        int processed = 0;
        int exported = 0;
        int skipped = 0;
        int failed = 0;

        for (Path source : files) {
            processed++;
            try {
                if (!Files.exists(source) || !Files.isRegularFile(source)) {
                    failed++;
                    logger.warn("Файл недоступен для экспорта: {}", source);
                    notifyProgress(progressCallback, processed, total);
                    continue;
                }

                Path relative = relativePath(commonRoot, source);
                Path destination = targetDirectory.resolve(relative).normalize();
                Files.createDirectories(destination.getParent());

                if (Files.exists(destination)) {
                    switch (conflictStrategy) {
                        case SKIP -> {
                            skipped++;
                            notifyProgress(progressCallback, processed, total);
                            continue;
                        }
                        case RENAME -> destination = nextAvailablePath(destination);
                        case OVERWRITE -> {
                        }
                    }
                }

                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                extractMailAttachmentsIfAny(source, destination, targetDirectory);
                exported++;
            } catch (Exception e) {
                failed++;
                logger.warn("Ошибка экспорта файла {}: {}", source, e.getMessage());
            }
            notifyProgress(progressCallback, processed, total);
        }

        return new ExportResult(total, exported, skipped, failed);
    }

    public ExportResult exportToZip(List<Path> inputFiles,
                                    Path zipPath,
                                    BiConsumer<Integer, Integer> progressCallback) {
        Set<Path> files = sanitize(inputFiles);
        int total = files.size();
        int processed = 0;
        int exported = 0;
        int failed = 0;

        try {
            if (zipPath.getParent() != null) {
                Files.createDirectories(zipPath.getParent());
            }
            Path commonRoot = commonRoot(files);

            try (OutputStream out = Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 ZipOutputStream zipOut = new ZipOutputStream(out)) {
                for (Path source : files) {
                    processed++;
                    try {
                        if (!Files.exists(source) || !Files.isRegularFile(source)) {
                            failed++;
                            logger.warn("Файл недоступен для ZIP-экспорта: {}", source);
                            notifyProgress(progressCallback, processed, total);
                            continue;
                        }

                        Path relative = relativePath(commonRoot, source);
                        String zipEntryName = relative.toString().replace('\\', '/');
                        ZipEntry entry = new ZipEntry(zipEntryName);
                        zipOut.putNextEntry(entry);
                        try (InputStream in = Files.newInputStream(source)) {
                            in.transferTo(zipOut);
                        }
                        zipOut.closeEntry();
                        exported++;
                    } catch (Exception e) {
                        failed++;
                        logger.warn("Ошибка добавления файла {} в ZIP: {}", source, e.getMessage());
                    }
                    notifyProgress(progressCallback, processed, total);
                }
            }
        } catch (IOException e) {
            logger.error("Критическая ошибка создания ZIP {}: {}", zipPath, e.getMessage(), e);
            return new ExportResult(total, exported, 0, failed + (total - processed));
        }

        return new ExportResult(total, exported, 0, failed);
    }

    private void extractMailAttachmentsIfAny(Path source,
                                             Path exportedMailPath,
                                             Path exportRoot) {
        if (!isEmlFile(source)) {
            return;
        }

        Path attachmentsDir = exportRoot.resolve("вложения");
        try {
            Files.createDirectories(attachmentsDir);
            Session session = Session.getDefaultInstance(new Properties());
            try (InputStream in = Files.newInputStream(exportedMailPath)) {
                MimeMessage message = new MimeMessage(session, in);
                Object content = message.getContent();
                extractAttachmentsFromContent(content, attachmentsDir);
            }
        } catch (Exception e) {
            logger.warn("Ошибка извлечения вложений из {}: {}", source, e.getMessage());
        }
    }

    private void extractAttachmentsFromContent(Object content,
                                               Path attachmentsDir) throws Exception {
        if (!(content instanceof Multipart multipart)) {
            return;
        }

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String disposition = part.getDisposition();
            String fileName = part.getFileName();
            boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                    || (fileName != null && !fileName.isBlank());

            if (part.getContent() instanceof Multipart nestedMultipart) {
                extractAttachmentsFromContent(nestedMultipart, attachmentsDir);
            }

            if (!isAttachment || fileName == null || fileName.isBlank()) {
                continue;
            }

            String safeName = sanitizeFileName(fileName);
            Path target = attachmentsDir.resolve(safeName);
            if (Files.exists(target)) {
                target = nextAvailablePath(target);
            }

            try (InputStream attachmentStream = part.getInputStream()) {
                Files.copy(attachmentStream, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                logger.warn("Ошибка сохранения вложения {}: {}", fileName, e.getMessage());
            }
        }
    }

    private boolean isEmlFile(Path source) {
        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".eml");
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\/:*?\"<>|]", "_");
    }

    private Set<Path> sanitize(List<Path> inputFiles) {
        Set<Path> normalized = new LinkedHashSet<>();
        for (Path path : inputFiles) {
            if (path != null) {
                normalized.add(path.toAbsolutePath().normalize());
            }
        }
        return normalized;
    }

    private Path commonRoot(Set<Path> files) {
        if (files.isEmpty()) {
            return Path.of("");
        }
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(Path::toString));
        Path first = sorted.get(0).getParent();
        Path last = sorted.get(sorted.size() - 1).getParent();

        if (first == null || last == null) {
            return Path.of("");
        }

        int min = Math.min(first.getNameCount(), last.getNameCount());
        Path result = first.getRoot() != null ? first.getRoot() : Path.of("");
        for (int i = 0; i < min; i++) {
            if (!first.getName(i).equals(last.getName(i))) {
                break;
            }
            result = result.resolve(first.getName(i));
        }
        return result;
    }

    private Path relativePath(Path commonRoot, Path source) {
        try {
            if (commonRoot.toString().isBlank()) {
                return source.getFileName();
            }
            return commonRoot.relativize(source);
        } catch (Exception ignored) {
            return source.getFileName();
        }
    }

    private Path nextAvailablePath(Path targetPath) {
        String fileName = targetPath.getFileName().toString();
        String baseName = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            baseName = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        Path parent = targetPath.getParent();
        int index = 1;
        Path candidate;
        do {
            candidate = parent.resolve(baseName + " (" + index + ")" + extension);
            index++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private void ensureDirectory(Path targetDirectory) {
        try {
            Files.createDirectories(targetDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Невозможно создать директорию экспорта: " + targetDirectory, e);
        }
    }

    private void notifyProgress(BiConsumer<Integer, Integer> progressCallback, int processed, int total) {
        if (progressCallback != null) {
            progressCallback.accept(processed, total);
        }
    }
}
