package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class IndexerService {
    private static final Logger logger = LoggerFactory.getLogger(IndexerService.class);

    private final Path indexPath;
    private final Tika tika = new Tika();

    private final Analyzer analyzer = new RussianAnalyzer();

    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "ico", "svg", "webp",
            "mp4", "mkv", "avi", "mov", "mp3", "wav", "flac",
            "zip", "rar", "7z", "iso", "exe", "dll", "sys", "tmp", "db"
    );

    public IndexerService(String indexPath) {
        this.indexPath = Paths.get(indexPath);
    }

    public void runIncrementalIndexing(String dataPath, BiConsumer<Integer, String> onProgress) throws IOException {
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        // УСКОРЕНИЕ: Используем большой буфер в RAM для быстрой обработки 2 млн файлов
        config.setRAMBufferSizeMB(512);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        try (FSDirectory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, config)) {

            try (DirectoryReader reader = getReader(dir, writer)) {

                // ПУНКТ 3: Удаляем из индекса файлы, которые физически удалены с диска
                if (reader != null) {
                    cleanDeletedFiles(writer, reader);
                }

                IndexSearcher searcher = (reader != null) ? new IndexSearcher(reader) : null;
                AtomicInteger counter = new AtomicInteger(0);

                // УСКОРЕНИЕ: Создаем пул потоков по количеству доступных ядер
                int threads = Runtime.getRuntime().availableProcessors();
                ExecutorService executor = Executors.newFixedThreadPool(threads);

                Files.walkFileTree(Paths.get(dataPath), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // Отправляем файл на обработку в свободный поток
                        executor.submit(() -> {
                            try {
                                processFile(writer, searcher, file, attrs, counter, onProgress);
                            } catch (Exception e) {
                                logger.error("Ошибка обработки: " + file, e);
                            }
                        });
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });

                // Ждем завершения всех потоков (может занять время на 450 ГБ)
                executor.shutdown();
                executor.awaitTermination(7, TimeUnit.DAYS);
            }

            // Финальный коммит и оптимизация индекса (ForceMerge)
            writer.commit();
            if (writer.hasDeletions()) {
                writer.forceMerge(1); // Полезно для очень больших индексов
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Индексация была прервана пользователем");
        }
    }

    private void cleanDeletedFiles(IndexWriter writer, DirectoryReader reader) throws IOException {
        int deletedCount = 0;
        for (int i = 0; i < reader.maxDoc(); i++) {
            Document doc = reader.storedFields().document(i);
            String pathString = doc.get("path");
            if (pathString != null && !Files.exists(Paths.get(pathString))) {
                writer.deleteDocuments(new Term("path", pathString));
                deletedCount++;
            }
        }
        if (deletedCount > 0) {
            writer.commit();
            logger.info("Очищено удаленных файлов из индекса: {}", deletedCount);
        }
    }

    private void processFile(IndexWriter writer, IndexSearcher searcher, Path file,
                             BasicFileAttributes attrs, AtomicInteger counter,
                             BiConsumer<Integer, String> onProgress) throws Exception {

        String pathString = file.toAbsolutePath().toString();
        if (shouldSkip(pathString)) return;

        long lastModified = attrs.lastModifiedTime().toMillis();

        // Проверяем: изменился ли файл или он уже есть в индексе?
        if (searcher != null && !isModified(searcher, pathString, lastModified)) {
            return;
        }

        indexFile(writer, file, lastModified);

        int currentCount = counter.incrementAndGet();
        // Чтобы GUI не зависал от миллионов обновлений, уведомляем раз в 100 файлов
        if (onProgress != null && currentCount % 100 == 0) {
            onProgress.accept(currentCount, file.getFileName().toString());
        }
    }

    private void indexFile(IndexWriter writer, Path path, long lastModified) {
        try {
            Document doc = new Document();
            String originalName = path.getFileName().toString();
            String searchableName = originalName.replace("_", " ").replace("-", " ");

            doc.add(new StringField("path", path.toString(), Field.Store.YES));
            doc.add(new TextField("filename", searchableName, Field.Store.YES));
            doc.add(new StoredField("display_name", originalName));
            doc.add(new StoredField("modified", lastModified));
            doc.add(new NumericDocValuesField("modified", lastModified));

            String content = tika.parseToString(path);
            if (content != null && !content.isBlank()) {
                doc.add(new TextField("content", content, Field.Store.NO));
            }

            writer.updateDocument(new Term("path", path.toString()), doc);
        } catch (Exception e) {
            logger.warn("Файл пропущен (Tika не смогла прочитать): {}", path);
        }
    }

    // Совместимость со старым вызовом
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
                return modField.numericValue().longValue() != currentModified;
            }
        }
        return true;
    }

    private boolean shouldSkip(String path) {
        String lower = path.toLowerCase();
        return SKIP_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || path.contains("~")
                || lower.contains("$recycle.bin")
                || lower.contains("system volume information");
    }
}