package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.config.SearchConfig;
import org.example.export.ConflictStrategy;
import org.example.export.ExportResult;
import org.example.export.ExportService;
import org.example.index.IndexRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(MainApp.class);

    private final SearchConfig config = SearchConfig.load();
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final IndexRegistry indexRegistry = new IndexRegistry(config.getIndexPath());

    private String hddPath = "";

    private final ObservableList<FileResult> nameResults = FXCollections.observableArrayList();
    private final ObservableList<FileResult> contentResults = FXCollections.observableArrayList();

    @Override
    public void start(Stage primaryStage) {
        logger.info("Запуск приложения. Lucene: {}", config.getLuceneVersion());
        primaryStage.setTitle("HDD Search Engine (Lucene 9)");

        TableView<FileResult> nameTable = createTable("Совпадения в названиях");
        nameTable.setItems(nameResults);

        TableView<FileResult> contentTable = createTable("Совпадения в содержимом");
        contentTable.setItems(contentResults);

        Label hddLabel = new Label("Источник не выбран");
        Label indexSizeLabel = new Label("Размер индекса: —");
        Label indexStatusLabel = new Label("Статус индекса: —");

        ComboBox<String> disksCombo = new ComboBox<>();
        disksCombo.getItems().addAll(getSystemRoots());
        if (!disksCombo.getItems().isEmpty()) {
            disksCombo.getSelectionModel().selectFirst();
            hddPath = disksCombo.getValue();
            hddLabel.setText(hddPath);
            refreshIndexInfo(indexSizeLabel, indexStatusLabel, hddPath);
        }

        Button btnChooseDirectory = new Button("Выбрать директорию");
        Button btnIndex = new Button("Проиндексировать выбранный диск");
        Button btnReindex = new Button("Переиндексировать");
        Button btnExport = new Button("Экспорт найденных файлов");

        ProgressBar pb = new ProgressBar(0);
        pb.setPrefWidth(220);

        TextField searchField = new TextField();
        searchField.setPromptText("Введите ключевое слово (используйте \"\" для точных фраз)...");
        Button btnSearch = new Button("Найти");

        Label statusLabel = new Label("Ожидание запуска...");
        statusLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        WebView previewArea = new WebView();
        VBox previewBox = new VBox(5, new Label("Предпросмотр фрагментов:"), previewArea);
        previewBox.setPadding(new Insets(10));
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        disksCombo.setOnAction(e -> {
            String selected = disksCombo.getValue();
            if (selected != null) {
                hddPath = selected;
                hddLabel.setText(hddPath);
                refreshIndexInfo(indexSizeLabel, indexStatusLabel, hddPath);
            }
        });

        btnChooseDirectory.setOnAction(e -> {
            File dir = new DirectoryChooser().showDialog(primaryStage);
            if (dir != null) {
                hddPath = dir.getAbsolutePath();
                hddLabel.setText(hddPath);
                if (!disksCombo.getItems().contains(hddPath)) {
                    disksCombo.getItems().add(hddPath);
                }
                disksCombo.getSelectionModel().select(hddPath);
                refreshIndexInfo(indexSizeLabel, indexStatusLabel, hddPath);
            }
        });

        btnIndex.setOnAction(e -> runIndexing(false, hddPath, btnIndex, btnReindex, pb, statusLabel, indexSizeLabel, indexStatusLabel));
        btnReindex.setOnAction(e -> runIndexing(true, hddPath, btnIndex, btnReindex, pb, statusLabel, indexSizeLabel, indexStatusLabel));

        btnSearch.setOnAction(e -> performSearch(searchField.getText()));
        btnExport.setOnAction(e -> exportResults(primaryStage, statusLabel, pb, btnExport));

        setupSelectionListener(nameTable, searchField, previewArea);
        setupSelectionListener(contentTable, searchField, previewArea);

        VBox leftPane = new VBox(10,
                new HBox(10, new Label("Диск/папка:"), disksCombo, btnChooseDirectory),
                new HBox(10, new Label("Выбрано:"), hddLabel),
                new HBox(10, btnIndex, btnReindex, btnExport, pb),
                indexStatusLabel,
                indexSizeLabel,
                statusLabel,
                new HBox(10, searchField, btnSearch),
                new Label("Поиск по именам:"), nameTable,
                new Label("Поиск по тексту:"), contentTable
        );
        leftPane.setPadding(new Insets(15));
        VBox.setVgrow(nameTable, Priority.ALWAYS);
        VBox.setVgrow(contentTable, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(leftPane, previewBox);
        splitPane.setDividerPositions(0.6);

        Scene scene = new Scene(splitPane, 1280, 820);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void runIndexing(boolean reindex,
                             String sourcePath,
                             Button btnIndex,
                             Button btnReindex,
                             ProgressBar pb,
                             Label statusLabel,
                             Label indexSizeLabel,
                             Label indexStatusLabel) {
        if (sourcePath == null || sourcePath.isBlank()) {
            showAlert("Внимание", "Сначала выберите диск или директорию");
            return;
        }

        String indexDirName = IndexRegistry.buildIndexDirectoryName(sourcePath);
        Path targetIndex = config.getIndexPath().resolve(indexDirName);

        if (!reindex && indexRegistry.findBySource(sourcePath).isPresent()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Индекс уже существует. Выполнить переиндексацию?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Индекс найден");
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.YES) {
                return;
            }
            reindex = true;
        }

        boolean finalReindex = reindex;
        btnIndex.setDisable(true);
        btnReindex.setDisable(true);
        pb.setProgress(-1);
        indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));

        backgroundExecutor.execute(() -> {
            try {
                if (finalReindex && Files.exists(targetIndex)) {
                    deleteDirectory(targetIndex);
                }

                IndexerService indexer = new IndexerService(config, targetIndex);
                indexer.runIncrementalIndexing(sourcePath, (count, fileName) -> Platform.runLater(() ->
                        statusLabel.setText(String.format("Индексация %s: %,d | %s", sourcePath, count, fileName))));

                long docs = indexer.countDocuments();
                long size = directorySize(targetIndex);
                indexRegistry.upsert(IndexRegistry.readyEntry(sourcePath, targetIndex, docs, size));

                Platform.runLater(() -> {
                    pb.setProgress(1);
                    btnIndex.setDisable(false);
                    btnReindex.setDisable(false);
                    statusLabel.setText("Индексация завершена: " + sourcePath);
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                });
            } catch (Exception ex) {
                indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));
                Platform.runLater(() -> {
                    btnIndex.setDisable(false);
                    btnReindex.setDisable(false);
                    pb.setProgress(0);
                    statusLabel.setText("Ошибка индексации: " + ex.getMessage());
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                    showAlert("Ошибка", ex.getMessage());
                });
            }
        });
    }

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        nameResults.clear();
        contentResults.clear();

        List<Path> indexPaths = indexRegistry.allReadyIndexPaths();
        if (indexPaths.isEmpty()) {
            showAlert("Поиск", "Нет готовых индексов. Сначала выполните индексацию диска.");
            return;
        }

        backgroundExecutor.execute(() -> {
            try (SearchService searcher = new SearchService(config, indexPaths)) {
                var nameHits = searcher.searchInFields(query, "filename");
                var contentHits = searcher.searchInFields(query, "content");
                Platform.runLater(() -> {
                    nameResults.addAll(nameHits);
                    contentResults.addAll(contentHits);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showAlert("Ошибка поиска", ex.getMessage()));
            }
        });
    }

    private void exportResults(Stage stage, Label statusLabel, ProgressBar progressBar, Button btnExport) {
        List<Path> files = collectUniqueResultPaths();
        if (files.isEmpty()) {
            showAlert("Экспорт", "Нет найденных файлов для экспорта.");
            return;
        }

        ChoiceDialog<String> modeDialog = new ChoiceDialog<>("В папку", "В папку", "В ZIP");
        modeDialog.setTitle("Экспорт");
        modeDialog.setHeaderText("Выберите формат экспорта");
        Optional<String> mode = modeDialog.showAndWait();
        if (mode.isEmpty()) {
            return;
        }

        ExportService exportService = new ExportService();
        btnExport.setDisable(true);
        progressBar.setProgress(0);

        if ("В папку".equals(mode.get())) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Выберите папку для экспорта");
            File targetDir = chooser.showDialog(stage);
            if (targetDir == null) {
                btnExport.setDisable(false);
                return;
            }

            ChoiceDialog<String> conflictDialog = new ChoiceDialog<>("Переименовать", "Перезаписать", "Пропустить", "Переименовать");
            conflictDialog.setTitle("Конфликт файлов");
            conflictDialog.setHeaderText("Если файл уже существует:");
            Optional<String> conflictAnswer = conflictDialog.showAndWait();
            if (conflictAnswer.isEmpty()) {
                btnExport.setDisable(false);
                return;
            }

            ConflictStrategy strategy = switch (conflictAnswer.get()) {
                case "Перезаписать" -> ConflictStrategy.OVERWRITE;
                case "Пропустить" -> ConflictStrategy.SKIP;
                default -> ConflictStrategy.RENAME;
            };

            backgroundExecutor.execute(() -> {
                ExportResult result = exportService.exportToDirectory(files, targetDir.toPath(), strategy,
                        (processed, total) -> Platform.runLater(() -> {
                            progressBar.setProgress(total == 0 ? 1 : (double) processed / total);
                            statusLabel.setText(String.format("Экспорт в папку: %d/%d", processed, total));
                        }));

                Platform.runLater(() -> {
                    btnExport.setDisable(false);
                    statusLabel.setText(String.format("Экспорт завершен. Успешно: %d, пропущено: %d, ошибок: %d",
                            result.exported(), result.skipped(), result.failed()));
                    showAlert("Экспорт завершен", statusLabel.getText());
                });
            });
        } else {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Сохранить ZIP-архив");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP архив", "*.zip"));
            fileChooser.setInitialFileName("search-results.zip");
            File zipFile = fileChooser.showSaveDialog(stage);
            if (zipFile == null) {
                btnExport.setDisable(false);
                return;
            }

            backgroundExecutor.execute(() -> {
                ExportResult result = exportService.exportToZip(files, zipFile.toPath(),
                        (processed, total) -> Platform.runLater(() -> {
                            progressBar.setProgress(total == 0 ? 1 : (double) processed / total);
                            statusLabel.setText(String.format("Экспорт в ZIP: %d/%d", processed, total));
                        }));

                Platform.runLater(() -> {
                    btnExport.setDisable(false);
                    statusLabel.setText(String.format("ZIP экспорт завершен. Успешно: %d, ошибок: %d",
                            result.exported(), result.failed()));
                    showAlert("Экспорт завершен", statusLabel.getText());
                });
            });
        }
    }

    private void refreshIndexInfo(Label sizeLabel, Label statusLabel, String sourcePath) {
        indexRegistry.findBySource(sourcePath).ifPresentOrElse(entry -> {
            statusLabel.setText("Статус индекса: " + entry.status() + " | Последняя индексация: " + entry.lastIndexed());
            sizeLabel.setText("Размер индекса: " + humanSize(entry.indexSizeBytes()) + " | Документов: " + entry.documentsCount());
        }, () -> {
            statusLabel.setText("Статус индекса: отсутствует");
            sizeLabel.setText("Размер индекса: —");
        });
    }

    private List<String> getSystemRoots() {
        List<String> roots = new ArrayList<>();
        for (File root : File.listRoots()) {
            roots.add(root.getAbsolutePath());
        }
        return roots;
    }

    private List<Path> collectUniqueResultPaths() {
        Set<String> unique = new LinkedHashSet<>();
        for (FileResult result : nameResults) {
            unique.add(result.getPath());
        }
        for (FileResult result : contentResults) {
            unique.add(result.getPath());
        }

        List<Path> paths = new ArrayList<>();
        for (String path : unique) {
            paths.add(Path.of(path));
        }
        return paths;
    }

    private TableView<FileResult> createTable(String title) {
        TableView<FileResult> table = new TableView<>();

        TableColumn<FileResult, String> colName = new TableColumn<>("Имя файла");
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colName.setPrefWidth(200);

        TableColumn<FileResult, String> colPath = new TableColumn<>("Полный путь");
        colPath.setCellValueFactory(new PropertyValueFactory<>("path"));
        colPath.setPrefWidth(400);

        table.getColumns().addAll(colName, colPath);

        table.setRowFactory(tv -> {
            TableRow<FileResult> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    openFile(row.getItem().getPath());
                }
            });
            return row;
        });

        return table;
    }

    private void setupSelectionListener(TableView<FileResult> table, TextField searchField, WebView preview) {
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                String keyword = searchField.getText();
                String path = newSelection.getPath();

                new Thread(() -> {
                    String htmlSnippets;
                    try (SearchService searchService = new SearchService(config, indexRegistry.allReadyIndexPaths())) {
                        htmlSnippets = searchService.getHighlights(path, keyword);
                    }
                    Platform.runLater(() -> preview.getEngine().loadContent(
                            "<html><body style='font-family: sans-serif; font-size: 13px;'>" +
                                    "<h3>Фрагменты из файла:</h3>" + htmlSnippets + "</body></html>"
                    ));
                }).start();
            }
        });
    }

    private void openFile(String path) {
        try {
            Desktop.getDesktop().open(new File(path));
        } catch (IOException e) {
            showAlert("Ошибка", "Не удалось открыть файл: " + e.getMessage());
        }
    }

    private long directorySize(Path path) {
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }

    private void showAlert(String title, String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        backgroundExecutor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
