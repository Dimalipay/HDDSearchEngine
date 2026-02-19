package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.analysis.AnalyzerProvider;
import org.example.config.SearchConfig;
import org.example.tika.TikaService;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public class IndexerService {
    private static final Logger logger = LoggerFactory.getLogger(IndexerService.class);

    private final Path indexPath;
    private final Analyzer analyzer;
    private final SearchConfig config;

    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "ico", "svg", "webp",
            "mp4", "mkv", "avi", "mov", "mp3", "wav", "flac",
            "zip", "rar", "7z", "iso", "exe", "dll", "sys", "tmp", "db"
    );

    public IndexerService(SearchConfig config) {
        this(config, config.getIndexPath());
    }

    public IndexerService(SearchConfig config, Path indexPath) {
        this.config = config;
        this.indexPath = indexPath;
        this.analyzer = AnalyzerProvider.getMultilingualAnalyzer();
    }

    // ── Совместимость: без отмены ────────────────────────────────────────────
    public void runIncrementalIndexing(String dataPath) throws IOException {
        runIncrementalIndexing(dataPath, null, () -> false);
    }

    public void runIncrementalIndexing(String dataPath,
                                       BiConsumer<Integer, String> onProgress) throws IOException {
        runIncrementalIndexing(dataPath, onProgress, () -> false);
    }

    // ── Основной метод с поддержкой отмены ──────────────────────────────────
    public void runIncrementalIndexing(String dataPath,
                                       BiConsumer<Integer, String> onProgress,
                                       BooleanSupplier cancellation) throws IOException {
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }

        IndexWriterConfig writerConfig = new IndexWriterConfig(analyzer);
        writerConfig.setRAMBufferSizeMB(this.config.getRamBufferSizeMB());
        writerConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        try (TikaService tikaService = new TikaService(config.getTikaMaxStringLength(), config.getTikaTimeoutSeconds());
             FSDirectory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, writerConfig)) {

            try (DirectoryReader reader = getReader(dir, writer)) {

                if (reader != null) {
                    cleanDeletedFiles(writer, reader);
                }

                IndexSearcher searcher = (reader != null) ? new IndexSearcher(reader) : null;
                AtomicInteger counter = new AtomicInteger(0);

                ExecutorService executor = Executors.newFixedThreadPool(this.config.getIndexingThreads());

                Files.walkFileTree(Paths.get(dataPath), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // Проверяем флаг отмены при каждом новом файле
                        if (cancellation.getAsBoolean()) {
                            executor.shutdownNow();
                            return FileVisitResult.TERMINATE;
                        }
                        try {
                            executor.submit(() -> {
                                if (cancellation.getAsBoolean()) return;
                                try {
                                    processFile(writer, searcher, file, attrs, counter, onProgress, tikaService);
                                } catch (Exception e) {
                                    logger.error("Ошибка обработки: " + file, e);
                                }
                            });
                        } catch (RejectedExecutionException rejected) {
                            logger.debug("Обработка файла {} пропущена: пул завершает работу.", file);
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });

                executor.shutdown();
                awaitExecutorTermination(executor);
            }

            // Если не было отмены — коммитим и оптимизируем
            if (!cancellation.getAsBoolean()) {
                writer.commit();
                if (writer.hasDeletions()) {
                    writer.forceMerge(1);
                }
            } else {
                logger.info("Индексация {} отменена пользователем.", dataPath);
                throw new CancellationException("Индексация остановлена пользователем");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Индексация была прервана");
        }
    }

    private void awaitExecutorTermination(ExecutorService executor) throws InterruptedException {
        if (!executor.awaitTermination(7, TimeUnit.DAYS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Потоки индексации не завершились корректно.");
            }
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
                             BiConsumer<Integer, String> onProgress,
                             TikaService tikaService) throws Exception {

        String pathString = file.toAbsolutePath().toString();
        if (shouldSkip(pathString)) return;

        long lastModified = attrs.lastModifiedTime().toMillis();

        if (searcher != null && !isModified(searcher, pathString, lastModified)) {
            return;
        }

        indexFile(writer, file, lastModified, tikaService);

        int currentCount = counter.incrementAndGet();
        if (onProgress != null && currentCount % 100 == 0) {
            onProgress.accept(currentCount, file.getFileName().toString());
        }
    }

    private void indexFile(IndexWriter writer, Path path, long lastModified, TikaService tikaService) {
        try {
            Document doc = new Document();
            String originalName = path.getFileName().toString();
            String searchableName = originalName.replace("_", " ").replace("-", " ");
            String filenameNoExt = stripExtension(originalName).replace("_", " ").replace("-", " ");

            doc.add(new StringField("path", path.toString(), Field.Store.YES));
            doc.add(new TextField("filename", searchableName, Field.Store.YES));
            doc.add(new TextField(AnalyzerProvider.FIELD_FILENAME_RU, searchableName, Field.Store.NO));
            doc.add(new TextField(AnalyzerProvider.FIELD_FILENAME_EN, searchableName, Field.Store.NO));
            doc.add(new TextField("filename_no_ext", filenameNoExt, Field.Store.NO));
            doc.add(new TextField(AnalyzerProvider.FIELD_FILENAME_NO_EXT_RU, filenameNoExt, Field.Store.NO));
            doc.add(new TextField(AnalyzerProvider.FIELD_FILENAME_NO_EXT_EN, filenameNoExt, Field.Store.NO));
            doc.add(new StoredField("display_name", originalName));
            doc.add(new StoredField("modified", lastModified));
            doc.add(new NumericDocValuesField("modified", lastModified));

            try {
                String content = tikaService.parseToString(path);
                if (content != null && !content.isBlank()) {
                    doc.add(new TextField("content", content, Field.Store.NO));
                    doc.add(new TextField(AnalyzerProvider.FIELD_CONTENT_RU, content, Field.Store.NO));
                    doc.add(new TextField(AnalyzerProvider.FIELD_CONTENT_EN, content, Field.Store.NO));
                } else {
                    logger.info("Файл {} проиндексирован без содержимого.", path);
                }
            } catch (Exception parseException) {
                logger.warn("Не удалось извлечь текст из {}. Причина: {}", path, parseException.getMessage());
            }

            writer.updateDocument(new Term("path", path.toString()), doc);
        } catch (Exception e) {
            logger.warn("Файл пропущен: {}", path);
        }
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

    public long countDocuments() {
        try (FSDirectory dir = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(dir)) {
                return 0;
            }
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                return reader.numDocs();
            }
        } catch (IOException e) {
            logger.warn("Не удалось получить количество документов: {}", e.getMessage());
            return 0;
        }
    }

    public Path getIndexPath() {
        return indexPath;
    }

    private boolean shouldSkip(String path) {
        String lower = path.toLowerCase();
        return SKIP_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || path.contains("~")
                || lower.contains("$recycle.bin")
                || lower.contains("system volume information");
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) return fileName;
        return fileName.substring(0, dotIndex);
    }
}
