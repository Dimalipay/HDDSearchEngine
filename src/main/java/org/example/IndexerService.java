package org.example;

import org.apache.lucene.analysis.ru.RussianAnalyzer;
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

public class IndexerService {
    private static final Logger logger = LoggerFactory.getLogger(IndexerService.class);

    private final Path indexPath;
    private final Tika tika = new Tika();
    private final RussianAnalyzer analyzer = new RussianAnalyzer();

    // Расширения, которые мы полностью игнорируем
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            "zip", "rar", "7z", "tar", "gz", "iso", "exe", "dll", "sys", "tmp"
    );

    public IndexerService(String indexPath) {
        this.indexPath = Paths.get(indexPath);
    }

    public void runIncrementalIndexing(String dataPath) throws IOException {
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }

        try (FSDirectory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {

            // Открываем Reader для проверки метаданных уже существующих в индексе файлов
            // Если индекс пустой, DirectoryReader выбросит IndexNotFoundException
            try (DirectoryReader reader = getReader(dir, writer)) {
                IndexSearcher searcher = (reader != null) ? new IndexSearcher(reader) : null;

                Files.walkFileTree(Paths.get(dataPath), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String pathString = file.toAbsolutePath().toString();

                        if (shouldSkip(pathString)) return FileVisitResult.CONTINUE;

                        long lastModified = attrs.lastModifiedTime().toMillis();

                        // Проверка: нужно ли читать файл с HDD?
                        if (searcher != null && !isModified(searcher, pathString, lastModified)) {
                            return FileVisitResult.CONTINUE; // Файл не изменился
                        }

                        indexFile(writer, file, lastModified);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        logger.error("Ошибка доступа к файлу {}: {}", file, exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            writer.commit();
        }
    }

    private DirectoryReader getReader(FSDirectory dir, IndexWriter writer) {
        try {
            // Пытаемся открыть Reader. Если индекса нет — это нормально для первого запуска.
            return DirectoryReader.open(writer);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isModified(IndexSearcher searcher, String path, long currentModified) throws IOException {
        // Поиск документа по уникальному пути
        TermQuery query = new TermQuery(new Term("path", path));
        TopDocs topDocs = searcher.search(query, 1);

        if (topDocs.totalHits.value > 0) {
            // Lucene 9 API: использование storedFields() для получения документа
            Document doc = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
            IndexableField modField = doc.getField("modified");

            if (modField != null && modField.numericValue() != null) {
                long indexedTime = modField.numericValue().longValue();
                return indexedTime != currentModified;
            }
        }
        return true; // Файла нет в индексе или данных недостаточно
    }

    private void indexFile(IndexWriter writer, Path path, long lastModified) {
        try {
            Document doc = new Document();
            String originalName = path.getFileName().toString();

            // ФИКС: Создаем "очищенное" имя для поиска
            // Заменяем подчеркивания и тире на пробелы, чтобы Lucene разбил их на слова
            String searchableName = originalName.replace("_", " ").replace("-", " ");

            doc.add(new StringField("path", path.toString(), Field.Store.YES));

            // В filename кладем очищенную строку
            doc.add(new TextField("filename", searchableName, Field.Store.YES));

            // Оставляем оригинальное имя в отдельном поле, если захотим его просто отображать без изменений
            doc.add(new StoredField("display_name", originalName));

            doc.add(new StoredField("modified", lastModified));
            doc.add(new NumericDocValuesField("modified", lastModified));

            String content = tika.parseToString(path);
            if (content != null && !content.isBlank()) {
                doc.add(new TextField("content", content, Field.Store.NO));
            }

            writer.updateDocument(new Term("path", path.toString()), doc);
        } catch (Exception e) {
            logger.warn("Ошибка индексации: " + path);
        }
    }

    private boolean shouldSkip(String path) {
        String lower = path.toLowerCase();
        // Пропускаем архивы, временные файлы Office и системные папки
        return SKIP_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || path.contains("~")
                || lower.contains("$recycle.bin")
                || lower.contains("system volume information");
    }
}