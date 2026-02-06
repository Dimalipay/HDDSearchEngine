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
        try (FSDirectory directory = FSDirectory.open(Paths.get(indexPath));
             DirectoryReader reader = DirectoryReader.open(directory)) {

            IndexSearcher searcher = new IndexSearcher(reader);
            Query query = new org.apache.lucene.queryparser.classic.QueryParser(field, analyzer).parse(keyword);
            TopDocs hits = searcher.search(query, 50);

            for (ScoreDoc scoreDoc : hits.scoreDocs) {
                var doc = searcher.storedFields().document(scoreDoc.doc);
                list.add(new FileResult(doc.get("filename"), doc.get("path")));
            }
        }
        return list;
    }

    public String getHighlights(String filePath, String searchTerm) {
        try {
            Tika tika = new Tika();
            String content = tika.parseToString(Paths.get(filePath));

            // Настраиваем подсветку: искомое слово будет в тегах <B>
            Formatter formatter = new SimpleHTMLFormatter("<B style='color:red;'>", "</B>");
            Query query = new QueryParser("content", analyzer).parse(searchTerm);
            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(formatter, scorer);

            // Разбиваем текст на фрагменты
            Fragmenter fragmenter = new SimpleSpanFragmenter(scorer, 150);
            highlighter.setTextFragmenter(fragmenter);

            // Получаем до 5 лучших фрагментов, разделенных многоточием
            String[] fragments = highlighter.getBestFragments(analyzer, "content", content, 5);

            if (fragments.length == 0) return "Совпадений в тексте не найдено (возможно, совпадение только в имени файла).";

            return String.join("\n... \n", fragments);
        } catch (Exception e) {
            return "Не удалось извлечь фрагменты: " + e.getMessage();
        }
    }
}