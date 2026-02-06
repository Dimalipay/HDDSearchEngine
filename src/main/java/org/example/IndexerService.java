package org.example;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class IndexerService {
    private static final Logger logger = LoggerFactory.getLogger(IndexerService.class);

    private final Path indexPath;
    private final Tika tika = new Tika();

    // ЛУЧШИЙ ВЫБОР: StandardAnalyzer
    // Он сохраняет спецсимволы (№) и отлично работает с цифрами, приводя всё к нижнему регистру
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    // 1. ИГНОРИРУЕМ ИЗОБРАЖЕНИЯ И МЕДИА (Чтобы не тратить ресурсы)
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            // Изображения
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "ico", "svg", "webp",
            // Видео и Аудио
            "mp4", "mkv", "avi", "mov", "mp3", "wav", "flac",
            // Системные и архивы
            "zip", "rar", "7z", "iso", "exe", "dll", "sys", "tmp", "db"
    );

    public IndexerService(String indexPath) {
        this.indexPath = Paths.get(indexPath);
    }

    public void runIncrementalIndexing(String dataPath, BiConsumer<Integer, String> onProgress) throws IOException {
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }

        // Используем StandardAnalyzer в конфигурации IndexWriter
        IndexWriterConfig config = new IndexWriterConfig(analyzer);

        try (FSDirectory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, config)) {

            try (DirectoryReader reader = getReader(dir, writer)) {
                IndexSearcher searcher = (reader != null) ? new IndexSearcher(reader) : null;
                AtomicInteger counter = new AtomicInteger(0);

                Files.walkFileTree(Paths.get(dataPath), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        int currentCount = counter.incrementAndGet();
                        if (onProgress != null) {
                            onProgress.accept(currentCount, file.getFileName().toString());
                        }

                        String pathString = file.toAbsolutePath().toString();
                        if (shouldSkip(pathString)) return FileVisitResult.CONTINUE;

                        long lastModified = attrs.lastModifiedTime().toMillis();

                        if (searcher != null && !isModified(searcher, pathString, lastModified)) {
                            return FileVisitResult.CONTINUE;
                        }

                        indexFile(writer, file, lastModified);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        logger.warn("Пропуск (нет доступа): {} - {}", file, exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            writer.commit();
        }
    }

    // Перегруженный метод для совместимости со старыми вызовами (например, из Main.java)
    public void runIncrementalIndexing(String dataPath) throws IOException {
        runIncrementalIndexing(dataPath, null);
    }

    private DirectoryReader getReader(FSDirectory dir, IndexWriter writer) {
        try {
            return DirectoryReader.open(writer);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isModified(IndexSearcher searcher, String path, long currentModified) throws IOException {
        TermQuery query = new TermQuery(new Term("path", path));
        TopDocs topDocs = searcher.search(query, 1);

        if (topDocs.totalHits.value > 0) {
            Document doc = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
            IndexableField modField = doc.getField("modified");

            if (modField != null && modField.numericValue() != null) {
                long indexedTime = modField.numericValue().longValue();
                return indexedTime != currentModified;
            }
        }
        return true;
    }

    private void indexFile(IndexWriter writer, Path path, long lastModified) {
        try {
            Document doc = new Document();
            String originalName = path.getFileName().toString();

            // "Умная" подготовка имени: заменяем символы-разделители на пробелы,
            // чтобы Lucene проиндексировал части имени как отдельные слова.
            // При этом StandardAnalyzer сохранит символ №, если он там есть.
            String searchableName = originalName.replace("_", " ").replace("-", " ");

            doc.add(new StringField("path", path.toString(), Field.Store.YES));
            doc.add(new TextField("filename", searchableName, Field.Store.YES));
            doc.add(new StoredField("display_name", originalName));
            doc.add(new StoredField("modified", lastModified));
            doc.add(new NumericDocValuesField("modified", lastModified));

            // Извлечение текста через Tika
            String content = tika.parseToString(path);
            if (content != null && !content.isBlank()) {
                doc.add(new TextField("content", content, Field.Store.NO));
            }

            writer.updateDocument(new Term("path", path.toString()), doc);
        } catch (Exception e) {
            logger.warn("Ошибка при индексации файла {}: {}", path, e.getMessage());
        }
    }

    private boolean shouldSkip(String path) {
        String lower = path.toLowerCase();
        return SKIP_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || path.contains("~")
                || lower.contains("$recycle.bin")
                || lower.contains("system volume information");
    }
}