package org.example.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IndexRegistry {
    private static final Logger logger = LoggerFactory.getLogger(IndexRegistry.class);
    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{(.*?)\\}", Pattern.DOTALL);

    private final Path registryFile;

    public IndexRegistry(Path indexesRoot) {
        this.registryFile = indexesRoot.resolve("indexes.json");
    }

    public synchronized List<IndexEntry> load() {
        if (!Files.exists(registryFile)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(registryFile);
            List<IndexEntry> entries = new ArrayList<>();
            Matcher matcher = OBJECT_PATTERN.matcher(json);
            while (matcher.find()) {
                String obj = matcher.group(1);
                entries.add(new IndexEntry(
                        readString(obj, "path").orElse(""),
                        readString(obj, "index_path").orElse(""),
                        readString(obj, "last_indexed").orElse(""),
                        readLong(obj, "documents_count").orElse(0L),
                        readLong(obj, "index_size_bytes").orElse(0L),
                        readString(obj, "status").orElse("UNKNOWN")
                ));
            }
            return entries;
        } catch (IOException e) {
            logger.warn("Не удалось прочитать {}: {}", registryFile, e.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void save(List<IndexEntry> entries) {
        try {
            Files.createDirectories(registryFile.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < entries.size(); i++) {
                IndexEntry e = entries.get(i);
                sb.append("  {\n")
                        .append("    \"path\": \"").append(escape(e.path())).append("\",\n")
                        .append("    \"index_path\": \"").append(escape(e.indexPath())).append("\",\n")
                        .append("    \"last_indexed\": \"").append(escape(e.lastIndexed())).append("\",\n")
                        .append("    \"documents_count\": ").append(e.documentsCount()).append(",\n")
                        .append("    \"index_size_bytes\": ").append(e.indexSizeBytes()).append(",\n")
                        .append("    \"status\": \"").append(escape(e.status())).append("\"\n")
                        .append("  }");
                if (i < entries.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("]\n");
            Files.writeString(registryFile, sb.toString());
        } catch (IOException e) {
            logger.error("Не удалось сохранить {}: {}", registryFile, e.getMessage(), e);
        }
    }

    public synchronized void upsert(IndexEntry entry) {
        List<IndexEntry> entries = load();
        entries.removeIf(it -> it.path().equals(entry.path()));
        entries.add(entry);
        entries.sort(Comparator.comparing(IndexEntry::path));
        save(entries);
    }

    /** Удаляет запись об индексе из реестра по пути источника. */
    public synchronized void remove(String sourcePath) {
        List<IndexEntry> entries = load();
        boolean removed = entries.removeIf(it -> it.path().equals(sourcePath));
        if (removed) {
            save(entries);
            logger.info("Запись индекса для {} удалена из реестра.", sourcePath);
        } else {
            logger.warn("Запись индекса для {} не найдена в реестре.", sourcePath);
        }
    }

    public synchronized Optional<IndexEntry> findBySource(String sourcePath) {
        return load().stream().filter(it -> it.path().equals(sourcePath)).findFirst();
    }

    public synchronized List<Path> allReadyIndexPaths() {
        List<Path> paths = new ArrayList<>();
        for (IndexEntry entry : load()) {
            if ("READY".equals(entry.status())) {
                paths.add(Paths.get(entry.indexPath()));
            }
        }
        return paths;
    }

    public static String buildIndexDirectoryName(String sourcePath) {
        String normalized = sourcePath.replace('\\', '_').replace('/', '_').replace(':', '_').replace(' ', '_');
        String compact = normalized.replaceAll("_+", "_");
        if (compact.length() > 40) {
            compact = compact.substring(0, 40);
        }
        return "index_" + compact + "_" + Integer.toHexString(sourcePath.hashCode());
    }

    public static IndexEntry readyEntry(String sourcePath, Path indexPath, long docs, long sizeBytes) {
        return new IndexEntry(sourcePath, indexPath.toString(), Instant.now().toString(), docs, sizeBytes, "READY");
    }

    public static IndexEntry failedEntry(String sourcePath, Path indexPath) {
        return new IndexEntry(sourcePath, indexPath.toString(), Instant.now().toString(), 0, 0, "FAILED");
    }

    private Optional<String> readString(String jsonObject, String key) {
        Pattern p = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"(.*?)\\\"");
        Matcher m = p.matcher(jsonObject);
        if (m.find()) {
            return Optional.of(unescape(m.group(1)));
        }
        return Optional.empty();
    }

    private Optional<Long> readLong(String jsonObject, String key) {
        Pattern p = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(jsonObject);
        if (m.find()) {
            return Optional.of(Long.parseLong(m.group(1)));
        }
        return Optional.empty();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public record IndexEntry(String path,
                             String indexPath,
                             String lastIndexed,
                             long documentsCount,
                             long indexSizeBytes,
                             String status) {
    }
}
