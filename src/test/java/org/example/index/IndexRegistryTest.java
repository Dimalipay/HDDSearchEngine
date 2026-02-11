package org.example.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IndexRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsEntries() {
        IndexRegistry registry = new IndexRegistry(tempDir);
        Path indexPath = tempDir.resolve("indexes/disk_d");

        registry.upsert(IndexRegistry.readyEntry("D:\\", indexPath, 12, 1024));

        var loaded = registry.load();
        assertEquals(1, loaded.size());
        assertEquals("D:\\", loaded.get(0).path());
        assertEquals(indexPath.toString(), loaded.get(0).indexPath());
        assertEquals("READY", loaded.get(0).status());
        assertFalse(registry.allReadyIndexPaths().isEmpty());
    }

    @Test
    void generatesStableIndexDirectoryName() {
        String one = IndexRegistry.buildIndexDirectoryName("D:/Docs");
        String two = IndexRegistry.buildIndexDirectoryName("D:/Docs");
        assertEquals(one, two);
        assertTrue(one.startsWith("index_"));
    }
}
