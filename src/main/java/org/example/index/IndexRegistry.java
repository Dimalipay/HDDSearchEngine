package org.example.index;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

/**
 * Реестр индексов — хранит метаданные о проиндексированных папках.
 *
 * Ранее использовал самописные регулярки для парсинга JSON, что ломалось
 * на кириллических путях, пробелах и обратных слешах Windows.
 * Теперь Jackson ObjectMapper — корректно обрабатывает любые символы.
 *
 * Важно: record IndexEntry имеет @JsonProperty на каждом компоненте,
 * чтобы Jackson мог десериализовать без флага -parameters у компилятора.
 */
public class IndexRegistry {
    private static final Logger logger = LoggerFactory.getLogger(IndexRegistry.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path registryFile;

    public IndexRegistry(Path indexesRoot) {
        this.registryFile = indexesRoot.resolve("indexes.json");
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    public synchronized List<IndexEntry> load() {
        if (!Files.exists(registryFile)) {
            return new ArrayList<>();
        }
        try {
            List<IndexEntry> entries = MAPPER.readValue(
                    registryFile.toFile(),
                    new TypeReference<>() {});
            return entries != null ? entries : new ArrayList<>();
        } catch (IOException e) {
            logger.warn("Не удалось прочитать {}: {}", registryFile, e.getMessage());
            // Повреждённый файл удаляем — при следующем upsert создастся заново
            tryDeleteCorrupted();
            return new ArrayList<>();
        }
    }

    public synchronized void save(List<IndexEntry> entries) {
        try {
            Files.createDirectories(registryFile.getParent());
            MAPPER.writeValue(registryFile.toFile(), entries);
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

    // ── Фабричные методы ─────────────────────────────────────────────────────

    public static String buildIndexDirectoryName(String sourcePath) {
        String normalized = sourcePath
                .replace('\\', '_').replace('/', '_')
                .replace(':', '_').replace(' ', '_');
        String compact = normalized.replaceAll("_+", "_");
        if (compact.length() > 40) compact = compact.substring(0, 40);
        return "index_" + compact + "_" + Integer.toHexString(sourcePath.hashCode());
    }

    public static IndexEntry readyEntry(String sourcePath, Path indexPath, long docs, long sizeBytes) {
        return new IndexEntry(sourcePath, indexPath.toString(),
                Instant.now().toString(), docs, sizeBytes, "READY");
    }

    public static IndexEntry failedEntry(String sourcePath, Path indexPath) {
        return new IndexEntry(sourcePath, indexPath.toString(),
                Instant.now().toString(), 0, 0, "FAILED");
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private void tryDeleteCorrupted() {
        try {
            Files.deleteIfExists(registryFile);
            logger.warn("Файл реестра {} повреждён и удалён, будет создан заново.", registryFile);
        } catch (IOException ignored) {}
    }

    // ── Модель ───────────────────────────────────────────────────────────────
    //
    // @JsonProperty на каждом компоненте record — обязательно!
    // Без этого Jackson не может определить имена параметров конструктора
    // при десериализации, если проект скомпилирован без флага -parameters.

    public record IndexEntry(
            @JsonProperty("path")              String path,
            @JsonProperty("indexPath")         String indexPath,
            @JsonProperty("lastIndexed")       String lastIndexed,
            @JsonProperty("documentsCount")    long   documentsCount,
            @JsonProperty("indexSizeBytes")    long   indexSizeBytes,
            @JsonProperty("status")            String status
    ) {}
}
