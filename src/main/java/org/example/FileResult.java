package org.example;

import javafx.beans.property.SimpleStringProperty;

public class FileResult {
    private final SimpleStringProperty name;
    private final SimpleStringProperty path;

    public FileResult(String name, String path) {
        this.name = new SimpleStringProperty(name);
        this.path = new SimpleStringProperty(path);
    }

    public String getName() { return name.get(); }
    public String getPath() { return path.get(); }
}