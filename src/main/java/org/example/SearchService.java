package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.highlight.*;
import org.example.analysis.AnalyzerProvider;
import org.example.config.SearchConfig;
import org.example.tika.TikaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SearchService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final SearchConfig config;

    // ТОЧНО ТАКОЙ ЖЕ АНАЛИЗАТОР, КАК В INDEXER_SERVICE
    // Это критически важно для того, чтобы поиск находил проиндексированные слова
    private final Analyzer analyzer;
    private final TikaService tikaService;

    public SearchService(SearchConfig config) {
        this.config = config;
        this.analyzer = AnalyzerProvider.get();
        this.tikaService = new TikaService(config.getTikaMaxStringLength(), config.getTikaTimeoutSeconds());
    }

    /**
     * Основной метод поиска для GUI (TableView)
     */
    public List<FileResult> searchInFields(String keyword, String field) throws Exception {
        List<FileResult> list = new ArrayList<>();

        try (FSDirectory directory = FSDirectory.open(config.getIndexPath())) {
            if (!DirectoryReader.indexExists(directory)) return list;

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);

                QueryParser parser = new QueryParser(field, analyzer);

                parser.setPhraseSlop(config.getPhraseSlop());
                parser.setDefaultOperator(config.getDefaultOperator() == SearchConfig.DefaultOperator.AND
                        ? QueryParser.Operator.AND
                        : QueryParser.Operator.OR);

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

            String content = tikaService.parseToString(file.toPath());

            // Настройка HTML-тегов для подсветки
            Formatter formatter = new SimpleHTMLFormatter("<B style='color:red;'>", "</B>");

            QueryParser parser = new QueryParser("content", analyzer);
            parser.setPhraseSlop(config.getPhraseSlop());
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
            logger.warn("Ошибка предпросмотра для {}: {}", filePath, e.getMessage());
            return "Ошибка предпросмотра: " + e.getMessage();
        }
    }

    /**
     * Консольный поиск (для отладки)
     */
    public void searchAndPrint(String keyword) {
        try (FSDirectory directory = FSDirectory.open(config.getIndexPath());
             DirectoryReader reader = DirectoryReader.open(directory)) {

            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer);
            parser.setPhraseSlop(config.getPhraseSlop());
            parser.setDefaultOperator(config.getDefaultOperator() == SearchConfig.DefaultOperator.AND
                    ? QueryParser.Operator.AND
                    : QueryParser.Operator.OR);
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

    @Override
    public void close() {
        tikaService.close();
    }
}
