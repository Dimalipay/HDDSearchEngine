package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
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
import java.nio.file.Path;
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
            AnalyzerProvider.FIELD_FILENAME_NO_EXT_RU,
            AnalyzerProvider.FIELD_FILENAME_NO_EXT_EN,
            "filename_no_ext",
            "filename"
    };

    private final SearchConfig config;
    private final Analyzer analyzer;
    private final TikaService tikaService;
    private final List<Path> indexPaths;

    public SearchService(SearchConfig config) {
        this(config, List.of(config.getIndexPath()));
    }

    public SearchService(SearchConfig config, List<Path> indexPaths) {
        this.config = config;
        this.indexPaths = indexPaths;
        this.analyzer = AnalyzerProvider.getMultilingualAnalyzer();
        // Для предпросмотра нужно уметь извлекать OCR-текст из скан-PDF/изображений,
        // иначе подсветка не найдёт фрагменты, хотя совпадение уже есть в индексе.
        this.tikaService = new TikaService(config.getTikaMaxStringLength(), config.getTikaTimeoutSeconds(),
                true, config.getOcrLanguage());
    }

    public List<FileResult> searchInFields(String keyword, String field) throws Exception {
        List<FileResult> list = new ArrayList<>();

        try (IndexReader reader = openCombinedReader()) {
            if (reader == null) {
                return list;
            }

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
        try (IndexReader reader = openCombinedReader()) {
            if (reader == null) {
                System.out.println("Нет доступных индексов для поиска.");
                return;
            }
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

    private QueryParser createParser(String field) {
        QueryParser parser;
        if ("content".equals(field)) {
            parser = new MultiFieldQueryParser(CONTENT_FIELDS, analyzer);
        } else if ("filename".equals(field)) {
            parser = new MultiFieldQueryParser(FILENAME_FIELDS, analyzer);
        } else {
            parser = new QueryParser(field, analyzer);
        }

        parser.setPhraseSlop(config.getPhraseSlop());
        parser.setDefaultOperator(config.getDefaultOperator() == SearchConfig.DefaultOperator.AND
                ? QueryParser.Operator.AND
                : QueryParser.Operator.OR);
        return parser;
    }

    private IndexReader openCombinedReader() throws Exception {
        List<DirectoryReader> readers = new ArrayList<>();
        for (Path path : indexPaths) {
            FSDirectory directory = FSDirectory.open(path);
            if (DirectoryReader.indexExists(directory)) {
                readers.add(DirectoryReader.open(directory));
            } else {
                directory.close();
            }
        }

        if (readers.isEmpty()) {
            return null;
        }
        if (readers.size() == 1) {
            return readers.get(0);
        }
        return new MultiReader(readers.toArray(new IndexReader[0]), true);
    }
}
