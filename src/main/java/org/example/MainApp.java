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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.export.ConflictStrategy;
import org.example.export.ExportResult;
import org.example.export.ExportService;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(MainApp.class);

    private final SearchConfig config = SearchConfig.load();
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private String hddPath = "";

    private final ObservableList<FileResult> nameResults = FXCollections.observableArrayList();
    private final ObservableList<FileResult> contentResults = FXCollections.observableArrayList();

    @Override
    public void start(Stage primaryStage) {
        logger.info("Запуск приложения. Lucene: {}", config.getLuceneVersion());
        primaryStage.setTitle("HDD Search Engine (Lucene 9)");

        // 1. СНАЧАЛА СОЗДАЕМ ТАБЛИЦЫ
        TableView<FileResult> nameTable = createTable("Совпадения в названиях");
        nameTable.setItems(nameResults);

        TableView<FileResult> contentTable = createTable("Совпадения в содержимом");
        contentTable.setItems(contentResults);

        // 2. СОЗДАЕМ ЭЛЕМЕНТЫ УПРАВЛЕНИЯ
        Label hddLabel = new Label("HDD не выбран");
        Button btnSelectHDD = new Button("Выбрать HDD");
        Button btnIndex = new Button("Обновить индекс");
        Button btnExport = new Button("Экспорт найденных файлов");
        ProgressBar pb = new ProgressBar(0);
        pb.setPrefWidth(200);

        TextField searchField = new TextField();
        searchField.setPromptText("Введите ключевое слово (используйте \"\" для точных фраз)...");
        Button btnSearch = new Button("Найти");

        // Статус-бар для отслеживания прогресса
        Label statusLabel = new Label("Ожидание запуска...");
        statusLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        // 3. ОБЛАСТЬ ПРЕДПРОСМОТРА
        WebView previewArea = new WebView();
        VBox previewBox = new VBox(5, new Label("Предпросмотр фрагментов:"), previewArea);
        previewBox.setPadding(new Insets(10));
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        // 4. ЛОГИКА КНОПОК
        btnSelectHDD.setOnAction(e -> {
            File dir = new DirectoryChooser().showDialog(primaryStage);
            if (dir != null) {
                hddPath = dir.getAbsolutePath();
                hddLabel.setText(hddPath);
            }
        });

        btnIndex.setOnAction(e -> {
            if (hddPath.isEmpty()) {
                showAlert("Внимание", "Сначала выберите папку на HDD");
                return;
            }
            btnIndex.setDisable(true);
            pb.setProgress(-1);

            new Thread(() -> {
                try {
                    // Передаем лямбду (count, fileName) для обновления статус-бара
                    new IndexerService(config).runIncrementalIndexing(hddPath, (count, fileName) -> {
                        Platform.runLater(() -> {
                            statusLabel.setText(String.format("Обработано файлов: %,d | Сейчас: %s", count, fileName));
                        });
                    });

                    Platform.runLater(() -> {
                        pb.setProgress(1);
                        btnIndex.setDisable(false);
                        statusLabel.setText("Индексация успешно завершена!");
                        showAlert("Готово", "Индексация завершена!");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        btnIndex.setDisable(false);
                        pb.setProgress(0);
                        statusLabel.setText("Ошибка: " + ex.getMessage());
                        showAlert("Ошибка", ex.getMessage());
                    });
                }
            }).start();
        });

        btnSearch.setOnAction(e -> performSearch(searchField.getText()));
        btnExport.setOnAction(e -> exportResults(primaryStage, statusLabel, pb, btnExport));

        // 5. СЛУШАТЕЛИ КЛИКОВ
        setupSelectionListener(nameTable, searchField, previewArea);
        setupSelectionListener(contentTable, searchField, previewArea);

        // 6. КОМПОНОВКА (Layout)
        VBox leftPane = new VBox(10,
                new HBox(10, btnSelectHDD, hddLabel, btnIndex, btnExport, pb),
                statusLabel, // Статус-бар под кнопками индексации
                new HBox(10, searchField, btnSearch),
                new Label("Поиск по именам:"), nameTable,
                new Label("Поиск по тексту:"), contentTable
        );
        leftPane.setPadding(new Insets(15));
        VBox.setVgrow(nameTable, Priority.ALWAYS);
        VBox.setVgrow(contentTable, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(leftPane, previewBox);
        splitPane.setDividerPositions(0.6); // 60% лево, 40% право

        Scene scene = new Scene(splitPane, 1200, 800);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        nameResults.clear();
        contentResults.clear();

        backgroundExecutor.execute(() -> {
            try (SearchService searcher = new SearchService(config)) {
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
                    // Используем SearchService для получения HTML-фрагментов с подсветкой
                    String htmlSnippets;
                    try (SearchService searchService = new SearchService(config)) {
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
