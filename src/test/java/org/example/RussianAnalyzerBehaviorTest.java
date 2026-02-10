package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.example.analysis.AnalyzerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RussianAnalyzerBehaviorTest {

    @TempDir
    Path tempDir;

    @Test
    void morphologyAndCaseAreNormalized() throws Exception {
        Analyzer analyzer = AnalyzerProvider.getRussianAnalyzer();
        Path indexPath = tempDir.resolve("index");
        try (FSDirectory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            Document doc1 = new Document();
            doc1.add(new TextField("content", "договору", Field.Store.YES));
            writer.addDocument(doc1);

            Document doc2 = new Document();
            doc2.add(new TextField("content", "ДОГОВОР", Field.Store.YES));
            writer.addDocument(doc2);
        }

        try (FSDirectory directory = FSDirectory.open(indexPath);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer);
            Query query = parser.parse("договор");
            TopDocs hits = searcher.search(query, 10);
            assertEquals(2, hits.totalHits.value, "Все формы договора должны находиться одним запросом");
        }
    }

    @Test
    void stopWordsAreRemovedFromTokens() throws IOException {
        List<String> tokens = analyzeTokens("и договор");
        assertEquals(List.of("договор"), tokens);

        List<String> onlyStopWord = analyzeTokens("и");
        assertTrue(onlyStopWord.isEmpty());
    }

    @Test
    void specialSymbolsAndNumbersHavePredictableTokens() throws IOException {
        assertEquals(List.of("123"), analyzeTokens("№123"));
        assertEquals(List.of("договор", "45"), analyzeTokens("договор № 45"));
    }

    private List<String> analyzeTokens(String text) throws IOException {
        Analyzer analyzer = AnalyzerProvider.getRussianAnalyzer();
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("field", new StringReader(text))) {
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(attr.toString());
            }
            stream.end();
        }
        return tokens;
    }
}
