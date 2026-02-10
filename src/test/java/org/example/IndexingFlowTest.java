package org.example;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.example.config.SearchConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void indexesSingleDocumentAndSupportsAndLogic() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("doc.txt");
        Files.writeString(file, "большой договор");

        SearchConfig config = SearchConfig.forTesting(tempDir.resolve("index"));
        IndexerService indexer = new IndexerService(config);
        indexer.runIncrementalIndexing(dataDir.toString());

        try (SearchService searchService = new SearchService(config)) {
            var hits = searchService.searchInFields("большой договор", "content");
            assertEquals(1, hits.size());
        }
    }

    @Test
    void incrementalIndexingUpdatesModifiedDocumentWithoutDuplicates() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("doc.txt");
        Files.writeString(file, "первая версия");

        SearchConfig config = SearchConfig.forTesting(tempDir.resolve("index"));
        IndexerService indexer = new IndexerService(config);
        indexer.runIncrementalIndexing(dataDir.toString());

        Files.writeString(file, "вторая версия");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));

        indexer.runIncrementalIndexing(dataDir.toString());

        try (SearchService searchService = new SearchService(config)) {
            assertEquals(1, searchService.searchInFields("вторая версия", "content").size());
            assertEquals(0, searchService.searchInFields("первая версия", "content").size());
        }

        assertEquals(1, countByPath(config.getIndexPath(), file.toString()));
    }

    @Test
    void deletedFilesAreRemovedFromIndex() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("doc.txt");
        Files.writeString(file, "тест удаления");

        SearchConfig config = SearchConfig.forTesting(tempDir.resolve("index"));
        IndexerService indexer = new IndexerService(config);
        indexer.runIncrementalIndexing(dataDir.toString());

        Files.delete(file);
        indexer.runIncrementalIndexing(dataDir.toString());

        try (SearchService searchService = new SearchService(config)) {
            assertTrue(searchService.searchInFields("doc.txt", "filename").isEmpty());
        }
    }


    @Test
    void multilingualSearchFindsEnglishRussianAndMixedQueries() throws Exception {
        Path dataDir = tempDir.resolve("data-multi");
        Files.createDirectories(dataDir);

        Path ruFile = dataDir.resolve("ru.txt");
        Files.writeString(ruFile, "договор подписан сторонами");

        Path enFile = dataDir.resolve("en.txt");
        Files.writeString(enFile, "agreement signed by both parties");

        Path mixFile = dataDir.resolve("mix.txt");
        Files.writeString(mixFile, "договор agreement confirmed");

        SearchConfig config = SearchConfig.forTesting(tempDir.resolve("index-multi"));
        IndexerService indexer = new IndexerService(config);
        indexer.runIncrementalIndexing(dataDir.toString());

        try (SearchService searchService = new SearchService(config)) {
            assertEquals(2, searchService.searchInFields("договор", "content").size());
            assertEquals(2, searchService.searchInFields("agreement", "content").size());
            assertEquals(1, searchService.searchInFields("договор agreement", "content").size());
        }
    }

    @Test
    void tikaFailuresDoNotBreakIndexing() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);

        Path brokenPdf = dataDir.resolve("broken.pdf");
        Files.write(brokenPdf, new byte[] {0x25, 0x50, 0x44, 0x46, 0x2D});

        Path hugeText = dataDir.resolve("huge.txt");
        Files.writeString(hugeText, "x".repeat(5000));

        SearchConfig config = SearchConfig.forTesting(
                tempDir.resolve("index"),
                64.0,
                1,
                2,
                SearchConfig.DefaultOperator.AND,
                1000,
                5
        );

        IndexerService indexer = new IndexerService(config);
        assertDoesNotThrow(() -> indexer.runIncrementalIndexing(dataDir.toString()));
    }

    private long countByPath(Path indexPath, String path) throws IOException {
        try (FSDirectory directory = FSDirectory.open(indexPath);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs hits = searcher.search(new TermQuery(new Term("path", path)), 10);
            return hits.totalHits.value;
        }
    }
}
