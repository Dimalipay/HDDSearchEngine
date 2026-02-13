package org.example.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PhotoPdfServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsPdfWithExpectedPageCount() throws Exception {
        Path folder = tempDir.resolve("photos");
        Files.createDirectories(folder);

        createImage(folder.resolve("20251211_132354.jpg"), Color.RED);
        createImage(folder.resolve("20251211_132355.jpg"), Color.BLUE);
        createImage(folder.resolve("20251211_132356.jpg"), Color.GREEN);
        createImage(folder.resolve("20251211_132357.jpg"), Color.YELLOW);
        createImage(folder.resolve("20251211_132358.jpg"), Color.BLACK);

        Path output = tempDir.resolve("out.pdf");
        PhotoPdfResult result = new PhotoPdfService().generatePdfFromFolder(folder, output);

        assertTrue(Files.exists(output));
        assertEquals(5, result.imageCount());
        assertEquals(2, result.pageCount());
    }

    @Test
    void failsWhenNoImagesInFolder() throws Exception {
        Path folder = tempDir.resolve("empty");
        Files.createDirectories(folder);

        PhotoPdfService service = new PhotoPdfService();
        assertThrows(IllegalArgumentException.class,
                () -> service.generatePdfFromFolder(folder, tempDir.resolve("out.pdf")));
    }

    private void createImage(Path path, Color color) throws Exception {
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        ImageIO.write(image, "jpg", path.toFile());
    }
}
