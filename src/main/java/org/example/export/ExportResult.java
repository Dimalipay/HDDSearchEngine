package org.example.export;

public record ExportResult(int total, int exported, int skipped, int failed) {
}
