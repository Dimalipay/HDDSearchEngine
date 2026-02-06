package org.example;

/**
 * Record для хранения найденной информации.
 * Java 17 позволяет описать модель одной строкой.
 */
public record SearchResult(String fileName, String path, float score) {}