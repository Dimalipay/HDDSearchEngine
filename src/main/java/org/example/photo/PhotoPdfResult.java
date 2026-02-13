package org.example.photo;

import java.nio.file.Path;

public record PhotoPdfResult(int imageCount, int pageCount, Path outputFile) {
}
