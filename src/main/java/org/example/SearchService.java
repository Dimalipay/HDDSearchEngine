package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
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

    // ТОЧНО ТАКОЙ ЖЕ АНАЛИЗАТОР, КАК В INDEXER_SERVICE
    // Это критически важно для того, чтобы поиск находил проиндексированные слова
    private final Analyzer analyzer = new RussianAnalyzer();

    public SearchService(String indexPath) {
        this.indexPath = indexPath;
    }

    /**
     * Основной метод поиска для GUI (TableView)
     */
    public List<FileResult> searchInFields(String keyword, String field) throws Exception {
        List<FileResult> list = new ArrayList<>();

        try (FSDirectory directory = FSDirectory.open(Paths.get(indexPath))) {
            if (!DirectoryReader.indexExists(directory)) return list;

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);

                QueryParser parser = new QueryParser(field, analyzer);

                // ПУНКТ 4: Настройка Slop (гибкость поиска фраз)
                // Позволяет находить слова, даже если между ними есть 2-3 других слова
                parser.setPhraseSlop(2);

                // Настройка оператора по умолчанию (AND делает поиск точнее)
                parser.setDefaultOperator(QueryParser.Operator.AND);

                Query query = parser.parse(keyword);
                TopDocs hits = searcher.search(query, 100);

                for (ScoreDoc scoreDoc : hits.scoreDocs) {
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    // Используем display_name для красивого отображения (с оригинальными _ и -)
                    String nameToShow = doc.get("display_name") != null ? doc.get("display_name") : doc.get("filename");
                    list.add(new FileResult(nameToShow, doc.get("path")));
                }
            }
        }
        return list;
    }

    /**
     * Генерация подсветки фрагментов текста для предпросмотра
     */
    public String getHighlights(String filePath, String searchTerm) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return "Файл не найден на диске.";

            Tika tika = new Tika();
            // Читаем только начало файла для быстроты (первые 100к символов)
            String content = tika.parseToString(file);

            // Настройка HTML-тегов для подсветки
            Formatter formatter = new SimpleHTMLFormatter("<B style='color:red;'>", "</B>");

            QueryParser parser = new QueryParser("content", analyzer);
            parser.setPhraseSlop(2);
            Query query = parser.parse(searchTerm);

            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(formatter, scorer);

            // Разбиваем текст на фрагменты по 150 символов
            Fragmenter fragmenter = new SimpleSpanFragmenter(scorer, 150);
            highlighter.setTextFragmenter(fragmenter);

            // Получаем 5 лучших фрагментов
            String[] fragments = highlighter.getBestFragments(analyzer, "content", content, 5);

            if (fragments == null || fragments.length == 0) {
                return "Совпадение найдено в названии файла или метаданных.";
            }

            return String.join("<br>...<br>", fragments);
        } catch (Exception e) {
            return "Ошибка предпросмотра: " + e.getMessage();
        }
    }

    /**
     * Консольный поиск (для отладки)
     */
    public void searchAndPrint(String keyword) {
        try (FSDirectory directory = FSDirectory.open(Paths.get(indexPath));
             DirectoryReader reader = DirectoryReader.open(directory)) {

            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer);
            parser.setPhraseSlop(2);
            Query query = parser.parse(keyword);

            TopDocs hits = searcher.search(query, 10);
            System.out.println("Найдено документов: " + hits.totalHits.value);

            for (ScoreDoc sd : hits.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                System.out.println("-> " + doc.get("path"));
            }
        } catch (Exception e) {
            System.err.println("Ошибка отладочного поиска: " + e.getMessage());
        }
    }
}