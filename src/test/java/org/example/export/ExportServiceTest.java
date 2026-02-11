package org.example.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsToDirectoryWithStructure() throws IOException {
        Path root = tempDir.resolve("source");
        Path nested = root.resolve("a/b");
        Files.createDirectories(nested);
        Path one = nested.resolve("one.txt");
        Path two = root.resolve("two.txt");
        Files.writeString(one, "doc-1");
        Files.writeString(two, "doc-2");

        Path exportDir = tempDir.resolve("export");
        ExportService service = new ExportService();
        ExportResult result = service.exportToDirectory(List.of(one, two), exportDir, ConflictStrategy.OVERWRITE, null);

        assertEquals(2, result.exported());
        assertEquals("doc-1", Files.readString(exportDir.resolve("a/b/one.txt")));
        assertEquals("doc-2", Files.readString(exportDir.resolve("two.txt")));
    }

    @Test
    void exportToDirectoryRenamesOnConflict() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "new-content");

        Path exportDir = tempDir.resolve("export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("source.txt"), "old-content");

        ExportService service = new ExportService();
        ExportResult result = service.exportToDirectory(List.of(source), exportDir, ConflictStrategy.RENAME, null);

        assertEquals(1, result.exported());
        assertEquals("old-content", Files.readString(exportDir.resolve("source.txt")));
        assertTrue(Files.exists(exportDir.resolve("source (1).txt")));
    }

    @Test
    void exportToZipKeepsRelativePathsAndSkipsMissing() throws Exception {
        Path root = tempDir.resolve("src");
        Files.createDirectories(root.resolve("x/y"));
        Path file = root.resolve("x/y/data.txt");
        Files.writeString(file, "zip-data");
        Path missing = root.resolve("x/y/missing.txt");

        Path zipPath = tempDir.resolve("out/result.zip");
        ExportService service = new ExportService();
        ExportResult result = service.exportToZip(List.of(file, missing), zipPath, null);

        assertEquals(1, result.exported());
        assertEquals(1, result.failed());

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            assertTrue(zip.getEntry("x/y/data.txt") != null);
        }
    }
}
