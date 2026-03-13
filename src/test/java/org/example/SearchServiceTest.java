package org.example;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.example.analysis.AnalyzerProvider;
import org.example.config.SearchConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SearchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void searchInFields_shouldFindByFilenameAndContent() throws Exception {
        Path indexPath = tempDir.resolve("idx");
        try (FSDirectory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(AnalyzerProvider.getMultilingualAnalyzer()))) {

            Document doc = new Document();
            doc.add(new StoredField("path", tempDir.resolve("report.txt").toString()));
            doc.add(new StoredField("display_name", "report.txt"));
            doc.add(new TextField("filename", "report", Field.Store.YES));
            doc.add(new TextField("content", "annual budget summary", Field.Store.NO));
            writer.addDocument(doc);
            writer.commit();
        }

        SearchConfig config = SearchConfig.forTesting(indexPath);
        try (SearchService service = new SearchService(config, List.of(indexPath))) {
            List<FileResult> filenameHits = service.searchInFields("report", "filename");
            List<FileResult> contentHits = service.searchInFields("budget", "content");

            assertFalse(filenameHits.isEmpty());
            assertFalse(contentHits.isEmpty());
            assertEquals("report.txt", filenameHits.get(0).getName());
        }
    }
}
