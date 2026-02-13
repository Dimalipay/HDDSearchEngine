package org.example.photo;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public class PhotoPdfService {
    private static final Logger logger = LoggerFactory.getLogger(PhotoPdfService.class);

    private static final int PAGE_WIDTH_PX = 2480;
    private static final int PAGE_HEIGHT_PX = 3508;
    private static final int MARGIN_PX = 50;
    private static final int GRID = 2;
    private static final int GAP_PX = 50;

    public PhotoPdfResult generatePdfFromFolder(Path folder, Path outputPdf) throws IOException {
        return generatePdfFromFolder(folder, outputPdf, null, () -> false);
    }

    public PhotoPdfResult generatePdfFromFolder(Path folder,
                                                Path outputPdf,
                                                ProgressListener progressListener,
                                                BooleanSupplier cancellation) throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Папка не существует: " + folder);
        }

        List<Path> imageFiles = loadImages(folder);
        if (imageFiles.isEmpty()) {
            throw new IllegalArgumentException("В выбранной папке нет поддерживаемых изображений");
        }

        List<BufferedImage> pages = new ArrayList<>();
        int totalImages = imageFiles.size();
        int processedImages = 0;

        BufferedImage currentPage = createBlankPage();
        Graphics2D g = currentPage.createGraphics();
        initGraphics(g);

        int slot = 0;
        for (Path imagePath : imageFiles) {
            if (cancellation.getAsBoolean()) {
                g.dispose();
                throw new IOException("Операция отменена пользователем");
            }

            processedImages++;
            notifyProgress(progressListener, "Сортировка изображений…", processedImages, totalImages);

            try {
                BufferedImage image = loadAndPrepareImage(imagePath);
                if (image == null) {
                    logger.warn("Изображение повреждено или не поддерживается: {}", imagePath);
                    continue;
                }

                placeOnPage(g, image, slot);
                slot++;

                if (slot == GRID * GRID) {
                    pages.add(currentPage);
                    g.dispose();
                    currentPage = createBlankPage();
                    g = currentPage.createGraphics();
                    initGraphics(g);
                    slot = 0;
                }
            } catch (Exception e) {
                logger.warn("Ошибка обработки изображения {}: {}", imagePath, e.getMessage());
            }
        }

        g.dispose();
        if (slot > 0) {
            pages.add(currentPage);
        }

        if (pages.isEmpty()) {
            throw new IOException("Не удалось подготовить ни одной страницы PDF");
        }

        notifyProgress(progressListener, "Сохранение PDF…", 0, pages.size());
        exportPdf(outputPdf, pages, progressListener, cancellation);

        return new PhotoPdfResult(totalImages, pages.size(), outputPdf);
    }

    private List<Path> loadImages(Path folder) throws IOException {
        try (var stream = Files.list(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isImage)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".bmp");
    }

    private BufferedImage loadAndPrepareImage(Path imagePath) throws Exception {
        BufferedImage source = ImageIO.read(imagePath.toFile());
        if (source == null) {
            return null;
        }
        BufferedImage corrected = applyExifOrientation(source, imagePath);
        if (corrected.getWidth() > corrected.getHeight()) {
            corrected = rotate90(corrected);
        }
        return corrected;
    }

    private BufferedImage applyExifOrientation(BufferedImage image, Path imagePath) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imagePath.toFile());
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return image;
            }
            int orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return switch (orientation) {
                case 3 -> rotate180(image);
                case 6 -> rotate90(image);
                case 8 -> rotate270(image);
                default -> image;
            };
        } catch (Exception e) {
            logger.debug("EXIF-ориентация не применена для {}: {}", imagePath, e.getMessage());
            return image;
        }
    }

    private void placeOnPage(Graphics2D g, BufferedImage image, int slot) {
        int col = slot % GRID;
        int row = slot / GRID;

        int cellWidth = (PAGE_WIDTH_PX - MARGIN_PX * 2 - GAP_PX) / GRID;
        int cellHeight = (PAGE_HEIGHT_PX - MARGIN_PX * 2 - GAP_PX) / GRID;

        int cellX = MARGIN_PX + col * (cellWidth + GAP_PX);
        int cellY = MARGIN_PX + row * (cellHeight + GAP_PX);

        double ratio = Math.min((double) cellWidth / image.getWidth(), (double) cellHeight / image.getHeight());
        int drawW = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        int drawH = Math.max(1, (int) Math.round(image.getHeight() * ratio));

        int x = cellX + (cellWidth - drawW) / 2;
        int y = cellY + (cellHeight - drawH) / 2;

        g.drawImage(image, x, y, drawW, drawH, null);
    }

    private void exportPdf(Path outputPdf,
                           List<BufferedImage> pages,
                           ProgressListener progressListener,
                           BooleanSupplier cancellation) throws IOException {
        if (outputPdf.getParent() != null) {
            Files.createDirectories(outputPdf.getParent());
        }

        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages.size(); i++) {
                if (cancellation.getAsBoolean()) {
                    throw new IOException("Операция отменена пользователем");
                }

                notifyProgress(progressListener, "Сохранение PDF…", i + 1, pages.size());
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                var pdImage = LosslessFactory.createFromImage(document, pages.get(i));
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.drawImage(pdImage, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }

            document.save(outputPdf.toFile());
        }
    }

    private BufferedImage createBlankPage() {
        BufferedImage page = new BufferedImage(PAGE_WIDTH_PX, PAGE_HEIGHT_PX, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PAGE_WIDTH_PX, PAGE_HEIGHT_PX);
        g.dispose();
        return page;
    }

    private void initGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private BufferedImage rotate90(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getHeight(), src.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        AffineTransform tx = new AffineTransform();
        tx.translate(src.getHeight(), 0);
        tx.rotate(Math.toRadians(90));
        g.drawImage(src, tx, null);
        g.dispose();
        return dst;
    }

    private BufferedImage rotate180(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        AffineTransform tx = new AffineTransform();
        tx.translate(src.getWidth(), src.getHeight());
        tx.rotate(Math.toRadians(180));
        g.drawImage(src, tx, null);
        g.dispose();
        return dst;
    }

    private BufferedImage rotate270(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getHeight(), src.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        AffineTransform tx = new AffineTransform();
        tx.translate(0, src.getWidth());
        tx.rotate(Math.toRadians(-90));
        g.drawImage(src, tx, null);
        g.dispose();
        return dst;
    }

    private void notifyProgress(ProgressListener progressListener, String state, int current, int total) {
        if (progressListener != null) {
            progressListener.onProgress(state, current, total);
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String status, int current, int total);
    }
}
