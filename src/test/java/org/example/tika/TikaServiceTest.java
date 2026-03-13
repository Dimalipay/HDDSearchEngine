package org.example.tika;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TikaServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void parseToString_shouldReadPlainTextWithoutOcr() throws Exception {
        Path file = tempDir.resolve("note.txt");
        Files.writeString(file, "hello tika");

        try (TikaService service = new TikaService(10000, 5, false, "rus+eng")) {
            String text = service.parseToString(file);
            assertTrue(text.contains("hello tika"));
        }
    }

    @Test
    void isEmailExtension_shouldBeCaseInsensitive() {
        assertTrue(TikaService.isEmailExtension("EML"));
        assertFalse(TikaService.isEmailExtension("pdf"));
    }
}
