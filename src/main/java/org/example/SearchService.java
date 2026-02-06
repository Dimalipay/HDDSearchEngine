package org.example;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
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

    // ЛУЧШИЙ ВЫБОР: Используем StandardAnalyzer для поддержки спецсимволов и цифр
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    public SearchService(String indexPath) {
        this.indexPath = indexPath;
    }

    /**
     * Консольный метод поиска (оставлен для отладки)
     */
    public void searchAndPrint(String keyword) {
        try (FSDirectory directory = FSDirectory.open(Paths.get(indexPath));
             DirectoryReader reader = DirectoryReader.open(directory)) {

            IndexSearcher searcher = new IndexSearcher(reader);

            System.out.println("\n[ РЕЗУЛЬТАТЫ ПОИСКА В НАЗВАНИЯХ ФАЙЛОВ ]");
            Query nameQuery = new QueryParser("filename", analyzer).parse(keyword);
            printHits(searcher.search(nameQuery, 20), searcher);

            System.out.println("\n" + "=".repeat(50));

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

                QueryParser parser = new QueryParser(field, analyzer);

                // 1. Используем AND: поиск "договор ромашка" найдет файлы, где есть ОБА слова.
                parser.setDefaultOperator(QueryParser.Operator.AND);

                // 2. ПУНКТ 4: Устанавливаем Phrase Slop.
                // Значение 2 означает, что в фразе "№1 договор" между №1 и словом договор
                // может стоять до 2-х других слов (например, "№1 срочный договор").
                parser.setPhraseSlop(2);

                Query query = parser.parse(keyword);
                TopDocs hits = searcher.search(query, 100);

                for (ScoreDoc scoreDoc : hits.scoreDocs) {
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    // Берем display_name для красивого отображения в таблице
                    String nameToShow = doc.get("display_name") != null ? doc.get("display_name") : doc.get("filename");
                    list.add(new FileResult(nameToShow, doc.get("path")));
                }
            }
        }
        return list;
    }

    /**
     * Метод для генерации фрагментов текста с подсветкой (HTML)
     */
    public String getHighlights(String filePath, String searchTerm) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return "Файл не найден на диске.";

            Tika tika = new Tika();
            // Ограничиваем объем читаемого текста для быстродействия
            String content = tika.parseToString(file);

            // Настройка формата подсветки (красный жирный текст)
            Formatter formatter = new SimpleHTMLFormatter("<B style='color:red;'>", "</B>");

            QueryParser parser = new QueryParser("content", analyzer);
            // Экранируем спецсимволы, если не используются кавычки,
            // но для поиска фразы в кавычках parse() сработает корректно сам
            Query query = parser.parse(searchTerm);

            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(formatter, scorer);

            // Делим текст на фрагменты по ~150 символов
            Fragmenter fragmenter = new SimpleSpanFragmenter(scorer, 150);
            highlighter.setTextFragmenter(fragmenter);

            // Получаем до 5 лучших фрагментов
            String[] fragments = highlighter.getBestFragments(analyzer, "content", content, 5);

            if (fragments == null || fragments.length == 0) {
                return "Совпадение найдено в метаданных файла или его имени.";
            }

            return String.join("<br>...<br>", fragments);
        } catch (Exception e) {
            return "Не удалось прочитать содержимое для предпросмотра: " + e.getMessage();
        }
    }
}