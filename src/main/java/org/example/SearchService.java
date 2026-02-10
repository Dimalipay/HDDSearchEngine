package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.FSDirectory;
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

    private static final String[] CONTENT_FIELDS = {
            AnalyzerProvider.FIELD_CONTENT_RU,
            AnalyzerProvider.FIELD_CONTENT_EN,
            "content"
    };

    private static final String[] FILENAME_FIELDS = {
            AnalyzerProvider.FIELD_FILENAME_RU,
            AnalyzerProvider.FIELD_FILENAME_EN,
            "filename"
    };

    private final SearchConfig config;
    private final Analyzer analyzer;
    private final TikaService tikaService;

    public SearchService(SearchConfig config) {
        this.config = config;
        this.analyzer = AnalyzerProvider.getMultilingualAnalyzer();
        this.tikaService = new TikaService(config.getTikaMaxStringLength(), config.getTikaTimeoutSeconds());
    }

    public List<FileResult> searchInFields(String keyword, String field) throws Exception {
        List<FileResult> list = new ArrayList<>();

        try (FSDirectory directory = FSDirectory.open(config.getIndexPath())) {
            if (!DirectoryReader.indexExists(directory)) return list;

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);

                QueryParser parser = createParser(field);
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
            File file = new File(filePath);
            if (!file.exists()) return "Файл не найден на диске.";

            String content = tikaService.parseToString(file.toPath());
            Formatter formatter = new SimpleHTMLFormatter("<B style='color:red;'>", "</B>");

            Query query = createParser("content").parse(searchTerm);

            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(formatter, scorer);

            Fragmenter fragmenter = new SimpleSpanFragmenter(scorer, 150);
            highlighter.setTextFragmenter(fragmenter);

            String[] ruFragments = highlighter.getBestFragments(AnalyzerProvider.getRussianAnalyzer(), "content", content, 5);
            String[] enFragments = highlighter.getBestFragments(AnalyzerProvider.getEnglishAnalyzer(), "content", content, 5);

            List<String> merged = new ArrayList<>();
            if (ruFragments != null) {
                for (String fragment : ruFragments) {
                    if (!fragment.isBlank() && !merged.contains(fragment)) {
                        merged.add(fragment);
                    }
                }
            }
            if (enFragments != null) {
                for (String fragment : enFragments) {
                    if (!fragment.isBlank() && !merged.contains(fragment)) {
                        merged.add(fragment);
                    }
                }
            }

            if (merged.isEmpty()) {
                return "Совпадение найдено в названии файла или метаданных.";
            }

            return String.join("<br>...<br>", merged);
        } catch (Exception e) {
            logger.warn("Ошибка предпросмотра для {}: {}", filePath, e.getMessage());
            return "Ошибка предпросмотра: " + e.getMessage();
        }
    }

    public void searchAndPrint(String keyword) {
        try (FSDirectory directory = FSDirectory.open(config.getIndexPath());
             DirectoryReader reader = DirectoryReader.open(directory)) {

            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = createParser("content");
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
