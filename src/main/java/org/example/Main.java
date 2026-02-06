package org.example;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

public class Main {
    public static void main(String[] args) {
        // Фикс кодировки
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
        } catch (UnsupportedEncodingException ignored) {}

        String ssdIndexPath = "D:/SearchIndexStorage"; // Твой путь к индексу
        String hddDataPath = "E:/Новицкая";           // Твой путь к HDD

        IndexerService indexer = new IndexerService(ssdIndexPath);
        SearchService searcher = new SearchService(ssdIndexPath);

        try {
            System.out.println("Обновление индекса (проверка изменений)...");
            indexer.runIncrementalIndexing(hddDataPath);

            System.out.print("\nВведите запрос для поиска: ");
            java.util.Scanner scanner = new java.util.Scanner(System.in, "UTF-8");
            String query = scanner.nextLine();

            searcher.searchAndPrint(query);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}