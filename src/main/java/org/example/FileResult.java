package org.example;

import javafx.beans.property.SimpleStringProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class FileResult {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SimpleStringProperty name;
    private final SimpleStringProperty path;

    public FileResult(String name, String path) {
        this.name = new SimpleStringProperty(name);
        this.path = new SimpleStringProperty(path);
    }

    public String getName() { return name.get(); }
    public String getPath() { return path.get(); }

    public String getTypeIcon() {
        String ext = extension();
        if (ext.equals("pdf")) return "📕";
        if (ext.matches("png|jpg|jpeg|bmp|gif|tiff|webp")) return "🖼";
        if (ext.matches("doc|docx|odt|rtf")) return "📝";
        if (ext.matches("xls|xlsx|csv")) return "📊";
        if (ext.matches("ppt|pptx")) return "📈";
        if (ext.matches("zip|rar|7z|tar|gz")) return "🗜";
        if (ext.matches("mp3|wav|ogg|flac")) return "🎵";
        if (ext.matches("mp4|mkv|avi|mov")) return "🎬";
        return "📄";
    }

    public String getSize() {
        try {
            long size = Files.size(Path.of(getPath()));
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / 1024.0 / 1024.0);
            return String.format("%.1f GB", size / 1024.0 / 1024.0 / 1024.0);
        } catch (IOException e) {
            return "—";
        }
    }

    public String getModified() {
        try {
            Instant modified = Files.getLastModifiedTime(Path.of(getPath())).toInstant();
            return DATE_FORMATTER.format(modified.atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (IOException e) {
            return "—";
        }
    }

    private String extension() {
        String fileName = getName();
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return "";
        return fileName.substring(idx + 1).toLowerCase();
    }
}
