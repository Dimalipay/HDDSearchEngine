package org.example;

import org.example.tika.TextFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextFileReaderTest {
    private static final String EXPECTED_TEXT = "Привет мир! Договор № 45.";

    @TempDir
    Path tempDir;

    @Test
    void readsUtf8WithoutBom() throws Exception {
        assertDecoded(writeEncoded("utf8.txt", EXPECTED_TEXT, Charset.forName("UTF-8"), new byte[0]));
    }

    @Test
    void readsUtf8WithBom() throws Exception {
        assertDecoded(writeEncoded("utf8_bom.txt", EXPECTED_TEXT, Charset.forName("UTF-8"), new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}));
    }

    @Test
    void readsUtf16Le() throws Exception {
        assertDecoded(writeEncoded("utf16le.txt", EXPECTED_TEXT, Charset.forName("UTF-16LE"), new byte[0]));
        assertDecoded(writeEncoded("utf16le_bom.txt", EXPECTED_TEXT, Charset.forName("UTF-16LE"), new byte[] {(byte) 0xFF, (byte) 0xFE}));
    }

    @Test
    void readsUtf16Be() throws Exception {
        assertDecoded(writeEncoded("utf16be.txt", EXPECTED_TEXT, Charset.forName("UTF-16BE"), new byte[0]));
        assertDecoded(writeEncoded("utf16be_bom.txt", EXPECTED_TEXT, Charset.forName("UTF-16BE"), new byte[] {(byte) 0xFE, (byte) 0xFF}));
    }

    @Test
    void readsCp1251() throws Exception {
        assertDecoded(writeEncoded("cp1251.txt", EXPECTED_TEXT, Charset.forName("windows-1251"), new byte[0]));
    }

    private void assertDecoded(Path path) throws Exception {
        String content = TextFileReader.read(path, 10_000);
        assertEquals(EXPECTED_TEXT, content);
        assertFalse(content.contains("\uFFFD"), "Не должно быть символов замены");
    }

    private Path writeEncoded(String fileName, String text, Charset charset, byte[] bom) throws Exception {
        byte[] data = text.getBytes(charset);
        byte[] content = new byte[bom.length + data.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(data, 0, content, bom.length, data.length);
        Path path = tempDir.resolve(fileName);
        Files.write(path, content);
        return path;
    }
}
