package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Хранит до {@value #MAX_SIZE} последних поисковых запросов в JSON-файле
 * {@code search-history.json} рядом с папкой индексов.
 *
 * <p>Формат файла — простой JSON-массив строк:</p>
 * <pre>["запрос1","запрос2",...]</pre>
 *
 * <p>Дубликаты не хранятся: повторный запрос перемещается в начало списка.</p>
 */
public class SearchHistoryService {

    private static final Logger log = LoggerFactory.getLogger(SearchHistoryService.class);
    private static final int MAX_SIZE = 50;

    private final Path historyFile;
    private final List<String> history = new ArrayList<>();

    public SearchHistoryService(Path indexDir) {
        this.historyFile = indexDir.resolve("search-history.json");
        load();
    }

    /** Добавляет запрос в начало истории (дубликат перемещается, не дублируется). */
    public void add(String query) {
        if (query == null || query.isBlank()) return;
        String q = query.trim();
        history.remove(q);
        history.add(0, q);
        if (history.size() > MAX_SIZE) history.subList(MAX_SIZE, history.size()).clear();
        save();
    }

    /** Возвращает все записи в порядке «свежие первыми». */
    public List<String> getAll() {
        return Collections.unmodifiableList(history);
    }

    /**
     * Возвращает записи, содержащие {@code prefix} (без учёта регистра).
     * Если prefix пустой — возвращает всю историю.
     */
    public List<String> filter(String prefix) {
        if (prefix == null || prefix.isBlank()) return getAll();
        String lower = prefix.trim().toLowerCase();
        List<String> result = new ArrayList<>();
        for (String entry : history)
            if (entry.toLowerCase().contains(lower)) result.add(entry);
        return result;
    }

    // ── private ──────────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(historyFile)) return;
        try {
            String json = Files.readString(historyFile, StandardCharsets.UTF_8).trim();
            // parse simple JSON array: ["a","b","c"]
            if (json.startsWith("[") && json.endsWith("]")) {
                String inner = json.substring(1, json.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String token : inner.split(",")) {
                        String s = token.trim();
                        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                            String entry = s.substring(1, s.length() - 1)
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\");
                            if (!entry.isBlank() && history.size() < MAX_SIZE)
                                history.add(entry);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Не удалось прочитать историю поиска: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(historyFile.getParent());
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"')
                  .append(history.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                  .append('"');
            }
            sb.append(']');
            Files.writeString(historyFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Не удалось сохранить историю поиска: {}", e.getMessage());
        }
    }
}
