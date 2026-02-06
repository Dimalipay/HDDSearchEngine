package org.example;

import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.highlight.*;
import org.apache.tika.Tika;
import java.io.File;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SearchService {
    private final String indexPath;
    private final RussianAnalyzer analyzer = new RussianAnalyzer();

    public SearchService(String indexPath) {
        this.indexPath = indexPath;
    }

    public void searchAndPrint(String keyword) {
        try (FSDirectory directory = FSDirectory.open(Paths.get(indexPath));
             DirectoryReader reader = DirectoryReader.open(directory)) {

            IndexSearcher searcher = new IndexSearcher(reader);

            // 1. Поиск по именам файлов
            System.out.println("\n[ РЕЗУЛЬТАТЫ ПОИСКА В НАЗВАНИЯХ ФАЙЛОВ ]");
            Query nameQuery = new QueryParser("filename", analyzer).parse(keyword);
            printHits(searcher.search(nameQuery, 20), searcher);

            System.out.println("\n" + "=".repeat(50));

            // 2. Поиск по содержимому документов
            System.out.println("[ РЕЗУЛЬТАТЫ ПОИСКА В СОДЕРЖИМОМ ДОКУМЕНТОВ ]");
            Query contentQuery = new QueryParser("content", analyzer).parse(keyword);
            printHits(searcher.search(contentQuery, 20), searcher);

        } catch (Exception e) {
            System.err.println("Ошибка при поиске: " + e.getMessage());
        }
    }

    private void printHits(TopDocs hits, IndexSearcher searcher) throws IOException {
        if (hits.totalHits.value == 0) {
            System.out.println("Ничего не найдено.");
            return;
        }

        for (ScoreDoc scoreDoc : hits.scoreDocs) {
            Document doc = searcher.storedFields().document(scoreDoc.doc);
            System.out.printf("Файл: %s | Путь: %s%n",
                    doc.get("filename"),
                    doc.get("path"));
        }
    }

    public List<FileResult> searchInFields(String keyword, String field) throws Exception {
        List<FileResult> list = new ArrayList<>();

        try (FSDirectory directory = FSDirectory.open(Paths.get(indexPath))) {
            if (!DirectoryReader.indexExists(directory)) return list;

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);

                // Настройка парсера
                QueryParser parser = new QueryParser(field, analyzer);

                // Разрешаем оператор "И" по умолчанию, чтобы слова без кавычек
                // искались более строго (если нужно), но для фраз это не критично
                parser.setDefaultOperator(QueryParser.Operator.AND);

                // Создаем запрос. Если в keyword есть кавычки, Lucene поймет, что это фраза.
                Query query = parser.parse(keyword);

                TopDocs hits = searcher.search(query, 100);

                for (ScoreDoc scoreDoc : hits.scoreDocs) {
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    String nameToShow = doc.get("display_name") != null ? doc.get("display_name") : doc.get("filename");
                    list.add(new FileResult(nameToShow, doc.get("path")));
                }
            }
        }
        return list;
    }

    public String getHighlights(String filePath, String searchTerm) {
        try {
            Tika tika = new Tika();
            // Ограничиваем чтение текста для предпросмотра (первые 100к символов),
            // чтобы не "вешать" GUI на гигантских файлах
            String content = tika.parseToString(new File(filePath));

            Formatter formatter = new SimpleHTMLFormatter("<B style='color:red;'>", "</B>");

            // Используем тот же QueryParser, что и при поиске
            QueryParser parser = new QueryParser("content", analyzer);
            Query query = parser.parse(searchTerm);

            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(formatter, scorer);

            // Фрагментация текста
            Fragmenter fragmenter = new SimpleSpanFragmenter(scorer, 150);
            highlighter.setTextFragmenter(fragmenter);

            String[] fragments = highlighter.getBestFragments(analyzer, "content", content, 5);

            if (fragments == null || fragments.length == 0) {
                return "Фраза найдена в названии или метаданных, но не в тексте.";
            }

            return String.join("<br>...<br>", fragments);
        } catch (Exception e) {
            return "Ошибка подсветки: " + e.getMessage();
        }
    }
}