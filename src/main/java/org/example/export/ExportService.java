package org.example.export;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.example.tika.TikaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Сервис экспорта найденных файлов — в папку или в ZIP.
 *
 * <h3>Email-письма</h3>
 * При экспорте для каждого письма (.eml / .msg / .pst / .ost / .mbox) автоматически
 * создаётся подпапка вида {@code <имя_письма>_вложения/} с физическими вложениями.
 *
 * <p><b>Ключевое отличие от предыдущей версии:</b> вместо Jakarta Mail
 * (работал только с .eml) теперь используется Apache Tika
 * {@link EmbeddedDocumentExtractor} — понимает все email-форматы.</p>
 *
 * <p>При ZIP-экспорте вложения упаковываются внутри архива в ту же подпапку.</p>
 */
public class ExportService {
    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    // ── Экспорт в папку ───────────────────────────────────────────────────────

    public ExportResult exportToDirectory(List<Path> inputFiles,
                                          Path targetDirectory,
                                          ConflictStrategy conflictStrategy,
                                          BiConsumer<Integer, Integer> progressCallback) {
        Set<Path> files = sanitize(inputFiles);
        ensureDirectory(targetDirectory);

        Path commonRoot = commonRoot(files);
        int total = files.size(), processed = 0, exported = 0, skipped = 0, failed = 0;

        for (Path source : files) {
            processed++;
            try {
                if (!Files.exists(source) || !Files.isRegularFile(source)) {
                    failed++;
                    logger.warn("Файл недоступен для экспорта: {}", source);
                    notifyProgress(progressCallback, processed, total);
                    continue;
                }

                Path relative    = relativePath(commonRoot, source);
                Path destination = targetDirectory.resolve(relative).normalize();
                Files.createDirectories(destination.getParent());

                if (Files.exists(destination)) {
                    switch (conflictStrategy) {
                        case SKIP -> {
                            skipped++;
                            notifyProgress(progressCallback, processed, total);
                            continue;
                        }
                        case RENAME    -> destination = nextAvailablePath(destination);
                        case OVERWRITE -> { /* overwrite below */ }
                    }
                }

                Files.copy(source, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                exported++;

                // Для письма — извлекаем вложения в подпапку рядом с ним
                extractEmailAttachmentsToDir(source, destination);

            } catch (Exception e) {
                failed++;
                logger.warn("Ошибка экспорта {}: {}", source, e.getMessage());
            }
            notifyProgress(progressCallback, processed, total);
        }

        return new ExportResult(total, exported, skipped, failed);
    }

    // ── Экспорт в ZIP ─────────────────────────────────────────────────────────

    public ExportResult exportToZip(List<Path> inputFiles,
                                    Path zipPath,
                                    BiConsumer<Integer, Integer> progressCallback) {
        Set<Path> files = sanitize(inputFiles);
        int total = files.size(), processed = 0, exported = 0, failed = 0;

        try {
            if (zipPath.getParent() != null) Files.createDirectories(zipPath.getParent());
            Path commonRoot = commonRoot(files);

            try (OutputStream out = Files.newOutputStream(zipPath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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

                        // Само письмо / файл
                        String zipEntry = relativePath(commonRoot, source)
                                .toString().replace('\\', '/');
                        zipOut.putNextEntry(new ZipEntry(zipEntry));
                        try (InputStream in = Files.newInputStream(source)) {
                            in.transferTo(zipOut);
                        }
                        zipOut.closeEntry();
                        exported++;

                        // Вложения письма — прямо в ZIP под подпапкой
                        extractEmailAttachmentsToZip(source, zipEntry, zipOut);

                    } catch (Exception e) {
                        failed++;
                        logger.warn("Ошибка добавления {} в ZIP: {}", source, e.getMessage());
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

    // ── Извлечение вложений: на диск ─────────────────────────────────────────

    /**
     * Если {@code source} — email-файл, извлекает вложения в подпапку
     * {@code <имя_экспортированного_письма_без_расширения>_вложения/}.
     *
     * <p>Каждое письмо получает свою папку — вложения от разных писем не смешиваются.</p>
     */
    private void extractEmailAttachmentsToDir(Path source, Path exportedFile) {
        if (!TikaService.isEmailExtension(getExtension(source.getFileName().toString()))) return;

        String baseName = stripExtension(exportedFile.getFileName().toString());
        Path attsDir    = exportedFile.getParent().resolve(baseName + "_вложения");

        try {
            int count = tikaExtractAttachments(source, attsDir, null, null);
            if (count > 0) {
                logger.info("Письмо '{}' → {} вложений в '{}'",
                        source.getFileName(), count, attsDir.getFileName());
            }
        } catch (Exception e) {
            logger.warn("Ошибка извлечения вложений из '{}': {}", source.getFileName(), e.getMessage());
        }
    }

    // ── Извлечение вложений: в ZIP ────────────────────────────────────────────

    /**
     * Если {@code source} — email-файл, добавляет вложения в открытый {@code zipOut}
     * под виртуальной подпапкой {@code <zipEntry_без_расширения>_вложения/}.
     */
    private void extractEmailAttachmentsToZip(Path source,
                                              String emailZipEntry,
                                              ZipOutputStream zipOut) {
        if (!TikaService.isEmailExtension(getExtension(source.getFileName().toString()))) return;

        String prefix = stripExtension(emailZipEntry) + "_вложения/";
        try {
            int count = tikaExtractAttachments(source, null, prefix, zipOut);
            if (count > 0) {
                logger.info("Письмо '{}' → {} вложений в ZIP под '{}'",
                        source.getFileName(), count, prefix);
            }
        } catch (Exception e) {
            logger.warn("Ошибка извлечения вложений из '{}' в ZIP: {}",
                    source.getFileName(), e.getMessage());
        }
    }

    // ── Ядро: Tika EmbeddedDocumentExtractor ─────────────────────────────────

    /**
     * Извлекает вложения через Tika. Два режима — диск или ZIP:
     * <ul>
     *   <li>если {@code outputDir != null} — сохраняет файлы на диск;</li>
     *   <li>если {@code zipOut != null}    — пишет в открытый ZIP-поток.</li>
     * </ul>
     *
     * <p>Работает для всех email-форматов (.eml / .msg / .pst / .ost / .mbox),
     * потому что использует Tika, а не Jakarta Mail.</p>
     *
     * @return количество успешно извлечённых вложений
     */
    private int tikaExtractAttachments(Path emailPath,
                                       Path outputDir,
                                       String zipPrefix,
                                       ZipOutputStream zipOut) throws Exception {
        if (outputDir != null) Files.createDirectories(outputDir);

        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context    = new ParseContext();
        context.set(Parser.class, parser);

        AtomicInteger count   = new AtomicInteger(0);
        Set<String> usedNames = new LinkedHashSet<>();

        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {

            @Override
            public boolean shouldParseEmbedded(Metadata meta) {
                String name  = meta.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                String ctype = meta.get(Metadata.CONTENT_TYPE);
                // Пропускаем безымянные части и тело письма (message/*)
                return name != null && !name.isBlank()
                        && (ctype == null || !ctype.startsWith("message/"));
            }

            @Override
            public void parseEmbedded(InputStream stream,
                                      org.xml.sax.ContentHandler handler,
                                      Metadata meta,
                                      boolean outputHtml) throws IOException {
                String rawName = meta.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                if (rawName == null || rawName.isBlank())
                    rawName = "attachment_" + (count.get() + 1);

                String safeName = rawName
                        .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").strip();
                if (safeName.isBlank())
                    safeName = "attachment_" + (count.get() + 1);

                safeName = uniqueName(usedNames, safeName);
                usedNames.add(safeName);

                byte[] bytes = stream.readAllBytes();
                if (bytes.length == 0) return;

                try {
                    if (outputDir != null) {
                        // Режим «на диск»
                        Files.copy(new ByteArrayInputStream(bytes),
                                outputDir.resolve(safeName),
                                StandardCopyOption.REPLACE_EXISTING);
                        logger.debug("  → {} ({} байт)", safeName, bytes.length);
                    } else {
                        // Режим «в ZIP»
                        String entry = zipPrefix + safeName;
                        zipOut.putNextEntry(new ZipEntry(entry));
                        zipOut.write(bytes);
                        zipOut.closeEntry();
                        logger.debug("  → ZIP:{} ({} байт)", entry, bytes.length);
                    }
                    count.incrementAndGet();
                } catch (IOException e) {
                    logger.warn("Ошибка сохранения вложения '{}': {}", safeName, e.getMessage());
                }
            }
        });

        Metadata rootMeta = new Metadata();
        try (InputStream in = Files.newInputStream(emailPath)) {
            parser.parse(in, new DefaultHandler(), rootMeta, context);
        } catch (SAXException | org.apache.tika.exception.TikaException ex) {
            // Частичный сбой — вложения до ошибки уже сохранены
            logger.warn("Частичная ошибка разбора '{}': {}", emailPath.getFileName(), ex.getMessage());
        }

        return count.get();
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private String uniqueName(Set<String> used, String name) {
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

    private Set<Path> sanitize(List<Path> inputFiles) {
        Set<Path> normalized = new LinkedHashSet<>();
        for (Path path : inputFiles) {
            if (path != null) normalized.add(path.toAbsolutePath().normalize());
        }
        return normalized;
    }

    private Path commonRoot(Set<Path> files) {
        if (files.isEmpty()) return Path.of("");
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(Path::toString));
        Path first = sorted.get(0).getParent();
        Path last  = sorted.get(sorted.size() - 1).getParent();
        if (first == null || last == null) return Path.of("");
        int min = Math.min(first.getNameCount(), last.getNameCount());
        Path result = first.getRoot() != null ? first.getRoot() : Path.of("");
        for (int i = 0; i < min; i++) {
            if (!first.getName(i).equals(last.getName(i))) break;
            result = result.resolve(first.getName(i));
        }
        return result;
    }

    private Path relativePath(Path commonRoot, Path source) {
        try {
            return commonRoot.toString().isBlank()
                    ? source.getFileName()
                    : commonRoot.relativize(source);
        } catch (Exception ignored) {
            return source.getFileName();
        }
    }

    private Path nextAvailablePath(Path targetPath) {
        String fileName = targetPath.getFileName().toString();
        int dot     = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext  = dot > 0 ? fileName.substring(dot)    : "";
        Path parent = targetPath.getParent();
        int idx = 1;
        Path candidate;
        do { candidate = parent.resolve(base + " (" + idx++ + ")" + ext); }
        while (Files.exists(candidate));
        return candidate;
    }

    private void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Невозможно создать директорию: " + dir, e);
        }
    }

    private void notifyProgress(BiConsumer<Integer, Integer> cb, int done, int total) {
        if (cb != null) cb.accept(done, total);
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 && dot < filename.length() - 1)
                ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
