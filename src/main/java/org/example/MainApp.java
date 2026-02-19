package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.photo.PhotoPdfResult;
import org.example.photo.PhotoPdfService;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Флаг отмены текущей индексации. Устанавливается в true кнопкой «Стоп». */
    private final AtomicBoolean indexingCancelled = new AtomicBoolean(false);
    /** Гарантирует, что одновременно выполняется только одна сессия индексации. */
    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);

    // ─── Fluent Design colour tokens ────────────────────────────────────────
    private static final String C_BG          = "#1c1c1e";
    private static final String C_SURFACE      = "#252526";
    private static final String C_PANEL        = "#2d2d2d";
    private static final String C_BORDER       = "#3d3d3d";
    private static final String C_ACCENT       = "#0078d4";
    private static final String C_ACCENT_HOVER = "#106ebe";
    private static final String C_TEXT         = "#ffffff";
    private static final String C_TEXT_SEC     = "#cccccc";
    private static final String C_TEXT_TER     = "#888888";
    private static final String C_CONTROL      = "#3d3d3d";
    private static final String C_CONTROL_H    = "#4d4d4d";
    private static final String C_DANGER       = "#c42b1c";
    private static final String C_DANGER_HOVER = "#a52315";

    @Override
    public void start(Stage primaryStage) {
        logger.info("Запуск приложения. Lucene: {}", config.getLuceneVersion());
        primaryStage.setTitle("HDD Search Engine");

        // ── Tables ──────────────────────────────────────────────────────────
        TableView<FileResult> nameTable = createTable();
        nameTable.setItems(nameResults);
        nameTable.setPlaceholder(styledPlaceholder("Нет результатов — выполните поиск"));

        TableView<FileResult> contentTable = createTable();
        contentTable.setItems(contentResults);
        contentTable.setPlaceholder(styledPlaceholder("Нет результатов — выполните поиск"));

        // ── Info labels ──────────────────────────────────────────────────────
        Label hddLabel         = metaLabel("Источник не выбран");
        Label indexSizeLabel   = metaLabel("Размер: —");
        Label indexStatusLabel = metaLabel("Статус: —");
        Label statusLabel      = new Label("Готов к работе");
        statusLabel.setStyle("-fx-text-fill:" + C_TEXT_TER + ";-fx-font-size:11px;-fx-font-family:'Segoe UI';");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        // ── Disk ComboBox ────────────────────────────────────────────────────
        ComboBox<String> disksCombo = new ComboBox<>();
        disksCombo.getItems().addAll(getSystemRoots());
        disksCombo.setMaxWidth(Double.MAX_VALUE);
        styleCombo(disksCombo);

        if (!disksCombo.getItems().isEmpty()) {
            disksCombo.getSelectionModel().selectFirst();
            hddPath = disksCombo.getValue();
            hddLabel.setText(hddPath);
            refreshIndexInfo(indexSizeLabel, indexStatusLabel, hddPath);
        }

        // ── Buttons ──────────────────────────────────────────────────────────
        Button btnChooseDirectory = fluentButton("📁  Обзор",             "secondary");
        Button btnIndex           = fluentButton("⚡  Индексировать",      "primary");
        Button btnReindex         = fluentButton("🔄  Переиндексировать",  "secondary");
        Button btnStop            = fluentButton("⏹  Остановить",          "danger");
        Button btnDeleteIndex     = fluentButton("🗑  Удалить индекс",     "secondary");
        Button btnExport          = fluentButton("📤  Экспорт файлов",     "secondary");
        Button btnPhotoPdf        = fluentButton("📷  Фото → PDF",         "secondary");

        // Кнопка «Стоп» доступна только во время индексации
        btnStop.setDisable(true);

        // ── Progress bar ─────────────────────────────────────────────────────
        ProgressBar pb = new ProgressBar(0);
        pb.setMaxWidth(Double.MAX_VALUE);
        pb.setPrefHeight(4);
        styleProgressBar(pb);

        // ── Search bar ───────────────────────────────────────────────────────
        TextField searchField = new TextField();
        searchField.setPromptText("Поиск...  (\"фраза\" для точного совпадения)");
        styleTextField(searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button btnSearch = fluentButton("🔍  Найти", "accent");
        btnSearch.setPrefWidth(110);

        // ── Preview ──────────────────────────────────────────────────────────
        WebView previewArea = new WebView();

        // ── Event handlers ───────────────────────────────────────────────────
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

        btnIndex.setOnAction(e ->
                runIndexing(false, hddPath, btnIndex, btnReindex, btnStop, pb, statusLabel, indexSizeLabel, indexStatusLabel));
        btnReindex.setOnAction(e ->
                runIndexing(true, hddPath, btnIndex, btnReindex, btnStop, pb, statusLabel, indexSizeLabel, indexStatusLabel));

        // ── «Остановить» — устанавливает флаг отмены ────────────────────────
        btnStop.setOnAction(e -> {
            indexingCancelled.set(true);
            btnStop.setDisable(true);
            statusLabel.setText("Остановка индексации...");
        });

        // ── «Удалить индекс» — диалог выбора и удаления ─────────────────────
        btnDeleteIndex.setOnAction(e -> deleteIndexDialog(primaryStage, indexSizeLabel, indexStatusLabel, statusLabel));

        btnSearch.setOnAction(e  -> performSearch(searchField.getText()));
        btnExport.setOnAction(e  -> exportResults(primaryStage, statusLabel, pb, btnExport));
        btnPhotoPdf.setOnAction(e -> createPdfFromPhotos(primaryStage));

        setupSelectionListener(nameTable, searchField, previewArea);
        setupSelectionListener(contentTable, searchField, previewArea);

        // ════════════════════════════════════════════════════════════════════
        //  LAYOUT
        // ════════════════════════════════════════════════════════════════════

        // ── Top bar ──────────────────────────────────────────────────────────
        Label appTitle = new Label("HDD Search Engine");
        appTitle.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:17px;-fx-font-weight:bold;-fx-font-family:'Segoe UI';");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        HBox searchRow = new HBox(8, searchField, btnSearch);
        searchRow.setAlignment(Pos.CENTER);
        searchRow.setMaxWidth(560);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        HBox topBar = new HBox(20, appTitle, titleSpacer, searchRow);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(12, 20, 12, 20));
        topBar.setStyle(
                "-fx-background-color:" + C_PANEL + ";" +
                        "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.45),10,0,0,3);"
        );

        // ── Sidebar ──────────────────────────────────────────────────────────
        HBox diskRow = new HBox(8, disksCombo, btnChooseDirectory);
        diskRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(disksCombo, Priority.ALWAYS);

        VBox indexInfo = new VBox(4, indexStatusLabel, indexSizeLabel);

        // Основные действия с индексом
        VBox primaryActions  = new VBox(6, btnIndex, btnReindex);
        // Стоп отдельно — визуально выделен
        VBox stopAction      = new VBox(4, btnStop);
        // Деструктивные и вспомогательные действия
        VBox secondaryActions = new VBox(6, btnDeleteIndex, btnExport, btnPhotoPdf);

        VBox sidebar = new VBox(12,
                sectionHeader("💾  ИСТОЧНИК"),
                diskRow,
                fieldLabel("Выбрано:"),
                hddLabel,
                divider(),
                sectionHeader("📊  ИНДЕКС"),
                indexInfo,
                divider(),
                sectionHeader("⚙️  ДЕЙСТВИЯ"),
                primaryActions,
                stopAction,
                divider(),
                secondaryActions,
                pb,
                statusLabel
        );
        sidebar.setPadding(new Insets(16));
        sidebar.setPrefWidth(272);
        sidebar.setMinWidth(240);
        sidebar.setStyle(
                "-fx-background-color:" + C_SURFACE + ";" +
                        "-fx-border-color:" + C_BORDER + ";" +
                        "-fx-border-width:0 1 0 0;"
        );

        // ── Results pane ─────────────────────────────────────────────────────
        VBox.setVgrow(nameTable,    Priority.ALWAYS);
        VBox.setVgrow(contentTable, Priority.ALWAYS);

        VBox resultsPane = new VBox(8,
                sectionHeader("🔤  СОВПАДЕНИЯ В НАЗВАНИЯХ"),
                nameTable,
                sectionHeader("📄  СОВПАДЕНИЯ В СОДЕРЖИМОМ"),
                contentTable
        );
        resultsPane.setPadding(new Insets(16));
        resultsPane.setStyle("-fx-background-color:" + C_BG + ";");
        VBox.setVgrow(resultsPane, Priority.ALWAYS);
        HBox.setHgrow(resultsPane, Priority.ALWAYS);

        // ── Preview pane ─────────────────────────────────────────────────────
        VBox previewPane = new VBox(8, sectionHeader("👁  ПРЕДПРОСМОТР"), previewArea);
        previewPane.setPadding(new Insets(16));
        previewPane.setPrefWidth(340);
        previewPane.setStyle(
                "-fx-background-color:" + C_SURFACE + ";" +
                        "-fx-border-color:" + C_BORDER + ";" +
                        "-fx-border-width:0 0 0 1;"
        );
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        // ── Main content ─────────────────────────────────────────────────────
        HBox mainContent = new HBox(0, sidebar, resultsPane, previewPane);
        HBox.setHgrow(resultsPane, Priority.ALWAYS);
        VBox.setVgrow(mainContent, Priority.ALWAYS);

        VBox root = new VBox(0, topBar, mainContent);
        VBox.setVgrow(mainContent, Priority.ALWAYS);
        root.setStyle("-fx-background-color:" + C_BG + ";");

        Scene scene = new Scene(root, 1280, 820);
        applyGlobalStyles(scene);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Новый функционал: удаление индекса
    // ════════════════════════════════════════════════════════════════════════

    private void deleteIndexDialog(Stage owner,
                                   Label indexSizeLabel,
                                   Label indexStatusLabel,
                                   Label statusLabel) {
        List<IndexRegistry.IndexEntry> entries = indexRegistry.load();
        if (entries.isEmpty()) {
            showAlert("Удаление индекса", "Нет сохранённых индексов для удаления.");
            return;
        }

        // Формируем список: "C:\ (READY, 1 234 567 B)"
        List<String> displayItems = new ArrayList<>();
        for (IndexRegistry.IndexEntry e : entries) {
            displayItems.add(String.format("%s  [%s, %s]",
                    e.path(), e.status(), humanSize(e.indexSizeBytes())));
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(displayItems.get(0), displayItems);
        dialog.setTitle("Удалить индекс");
        dialog.setHeaderText("Выберите индекс для удаления:");
        dialog.setContentText("Индекс:");

        Optional<String> choice = dialog.showAndWait();
        if (choice.isEmpty()) return;

        int selectedIdx = displayItems.indexOf(choice.get());
        IndexRegistry.IndexEntry toDelete = entries.get(selectedIdx);

        // Подтверждение
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Удалить индекс для:\n" + toDelete.path() + "\n\nДействие необратимо.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Подтверждение удаления");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.YES) return;

        // Удаляем файлы индекса в фоне
        statusLabel.setText("Удаление индекса: " + toDelete.path() + "...");
        backgroundExecutor.execute(() -> {
            try {
                Path indexDir = Path.of(toDelete.indexPath());
                if (Files.exists(indexDir)) {
                    deleteDirectory(indexDir);
                }
                indexRegistry.remove(toDelete.path());

                Platform.runLater(() -> {
                    statusLabel.setText("Индекс удалён: " + toDelete.path());
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, hddPath);
                    showAlert("Готово", "Индекс для «" + toDelete.path() + "» успешно удалён.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Ошибка удаления индекса: " + ex.getMessage());
                    showAlert("Ошибка", "Не удалось удалить индекс: " + ex.getMessage());
                });
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Индексация с поддержкой отмены
    // ════════════════════════════════════════════════════════════════════════

    private void runIndexing(boolean reindex,
                             String sourcePath,
                             Button btnIndex,
                             Button btnReindex,
                             Button btnStop,
                             ProgressBar pb,
                             Label statusLabel,
                             Label indexSizeLabel,
                             Label indexStatusLabel) {
        if (sourcePath == null || sourcePath.isBlank()) {
            showAlert("Внимание", "Сначала выберите диск или директорию");
            return;
        }
        if (!indexingInProgress.compareAndSet(false, true)) {
            showAlert("Индексация", "Индексация уже выполняется. Дождитесь завершения или нажмите «Стоп».");
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
            if (answer.isEmpty() || answer.get() != ButtonType.YES) return;
            reindex = true;
        }

        boolean finalReindex = reindex;

        // Сбрасываем флаг и меняем состояние кнопок
        indexingCancelled.set(false);
        btnIndex.setDisable(true);
        btnReindex.setDisable(true);
        btnStop.setDisable(false);
        pb.setProgress(-1);
        indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));

        backgroundExecutor.execute(() -> {
            try {
                if (finalReindex && Files.exists(targetIndex)) {
                    deleteDirectory(targetIndex);
                }

                IndexerService indexer = new IndexerService(config, targetIndex);
                indexer.runIncrementalIndexing(
                        sourcePath,
                        (count, fileName) -> Platform.runLater(() ->
                                statusLabel.setText(String.format("Индексация %s: %,d | %s", sourcePath, count, fileName))),
                        indexingCancelled::get   // передаём флаг отмены
                );

                long docs = indexer.countDocuments();
                long size = directorySize(targetIndex);
                indexRegistry.upsert(IndexRegistry.readyEntry(sourcePath, targetIndex, docs, size));

                Platform.runLater(() -> {
                    pb.setProgress(1);
                    btnIndex.setDisable(false);
                    btnReindex.setDisable(false);
                    btnStop.setDisable(true);
                    statusLabel.setText("Индексация завершена: " + sourcePath);
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                });

            } catch (CancellationException cancelled) {
                // Пользователь нажал «Стоп» — сохраняем частичный индекс как FAILED
                indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));
                Platform.runLater(() -> {
                    pb.setProgress(0);
                    btnIndex.setDisable(false);
                    btnReindex.setDisable(false);
                    btnStop.setDisable(true);
                    statusLabel.setText("Индексация остановлена: " + sourcePath);
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                });

            } catch (Exception ex) {
                indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));
                Platform.runLater(() -> {
                    pb.setProgress(0);
                    btnIndex.setDisable(false);
                    btnReindex.setDisable(false);
                    btnStop.setDisable(true);
                    statusLabel.setText("Ошибка индексации: " + ex.getMessage());
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                    showAlert("Ошибка", ex.getMessage());
                });
            } finally {
                indexingInProgress.set(false);
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Остальная бизнес-логика (без изменений)
    // ════════════════════════════════════════════════════════════════════════

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
                var nameHits    = searcher.searchInFields(query, "filename");
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
        if (mode.isEmpty()) return;

        ExportService exportService = new ExportService();
        btnExport.setDisable(true);
        progressBar.setProgress(0);

        if ("В папку".equals(mode.get())) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Выберите папку для экспорта");
            File targetDir = chooser.showDialog(stage);
            if (targetDir == null) { btnExport.setDisable(false); return; }

            ChoiceDialog<String> conflictDialog = new ChoiceDialog<>("Переименовать", "Перезаписать", "Пропустить", "Переименовать");
            conflictDialog.setTitle("Конфликт файлов");
            conflictDialog.setHeaderText("Если файл уже существует:");
            Optional<String> conflictAnswer = conflictDialog.showAndWait();
            if (conflictAnswer.isEmpty()) { btnExport.setDisable(false); return; }

            ConflictStrategy strategy = switch (conflictAnswer.get()) {
                case "Перезаписать" -> ConflictStrategy.OVERWRITE;
                case "Пропустить"   -> ConflictStrategy.SKIP;
                default             -> ConflictStrategy.RENAME;
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
            if (zipFile == null) { btnExport.setDisable(false); return; }

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

    private void createPdfFromPhotos(Stage owner) {
        DirectoryChooser folderChooser = new DirectoryChooser();
        folderChooser.setTitle("Выберите папку с фотографиями");
        File folder = folderChooser.showDialog(owner);
        if (folder == null) return;

        FileChooser saveChooser = new FileChooser();
        saveChooser.setTitle("Сохранить PDF из фото");
        saveChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        saveChooser.setInitialFileName(folder.getName() + "_photos.pdf");
        File outputPdf = saveChooser.showSaveDialog(owner);
        if (outputPdf == null) return;

        Stage progressStage = new Stage();
        progressStage.initOwner(owner);
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.setTitle("Фото → PDF");

        Label status = new Label("Подготовка...");
        status.setStyle("-fx-text-fill:#cccccc;-fx-font-size:12px;-fx-font-family:'Segoe UI';");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(320);
        styleProgressBar(progressBar);

        Button cancel = fluentButton("Отмена", "secondary");

        VBox box = new VBox(12, status, progressBar, cancel);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:" + C_PANEL + ";");
        progressStage.setScene(new Scene(box));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancel.setOnAction(e -> cancelled.set(true));

        PhotoPdfService service = new PhotoPdfService();
        backgroundExecutor.execute(() -> {
            try {
                PhotoPdfResult result = service.generatePdfFromFolder(folder.toPath(), outputPdf.toPath(),
                        (text, current, total) -> Platform.runLater(() -> {
                            status.setText(text + " " + current + "/" + total);
                            progressBar.setProgress(total == 0 ? 0 : (double) current / total);
                        }),
                        cancelled::get
                );
                Platform.runLater(() -> {
                    progressStage.close();
                    showPhotoPdfResultDialog(result);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    progressStage.close();
                    showAlert("Ошибка", "Не удалось создать PDF: " + ex.getMessage());
                });
            }
        });

        progressStage.show();
    }

    private void showPhotoPdfResultDialog(PhotoPdfResult result) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("PDF создан");
        alert.setHeaderText("Создан PDF: " + result.outputFile());
        alert.setContentText("Страниц: " + result.pageCount() + "\nИзображений: " + result.imageCount());

        ButtonType openFile   = new ButtonType("Открыть файл");
        ButtonType openFolder = new ButtonType("Открыть папку");
        ButtonType close      = new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(openFile, openFolder, close);

        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty()) return;

        try {
            if (answer.get() == openFile) {
                Desktop.getDesktop().open(result.outputFile().toFile());
            } else if (answer.get() == openFolder && result.outputFile().getParent() != null) {
                Desktop.getDesktop().open(result.outputFile().getParent().toFile());
            }
        } catch (Exception e) {
            showAlert("Ошибка", "Не удалось открыть результат: " + e.getMessage());
        }
    }

    private void refreshIndexInfo(Label sizeLabel, Label statusLabel, String sourcePath) {
        indexRegistry.findBySource(sourcePath).ifPresentOrElse(entry -> {
            statusLabel.setText("Статус: " + entry.status() + "  ·  " + entry.lastIndexed());
            sizeLabel.setText("Размер: " + humanSize(entry.indexSizeBytes()) + "  ·  " + entry.documentsCount() + " док.");
        }, () -> {
            statusLabel.setText("Статус: индекс отсутствует");
            sizeLabel.setText("Размер: —");
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI helpers
    // ════════════════════════════════════════════════════════════════════════

    private Button fluentButton(String text, String type) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle(btnStyle(type));
        btn.setOnMouseEntered(e -> { if (!btn.isDisabled()) btn.setStyle(btnStyleHover(type)); });
        btn.setOnMouseExited(e  -> { if (!btn.isDisabled()) btn.setStyle(btnStyle(type)); });
        btn.setOnMousePressed(e -> { if (!btn.isDisabled()) btn.setStyle(btnStylePressed(type)); });
        btn.setOnMouseReleased(e -> { if (!btn.isDisabled()) btn.setStyle(btnStyle(type)); });
        return btn;
    }

    private String btnStyle(String type) {
        String base = "-fx-font-family:'Segoe UI';-fx-font-size:12px;-fx-padding:7 14 7 14;" +
                "-fx-background-radius:4;-fx-border-radius:4;-fx-cursor:hand;";
        return switch (type) {
            case "primary", "accent" ->
                    base + "-fx-background-color:" + C_ACCENT + ";-fx-text-fill:" + C_TEXT + ";-fx-border-color:transparent;";
            case "danger" ->
                    base + "-fx-background-color:" + C_DANGER + ";-fx-text-fill:" + C_TEXT + ";-fx-border-color:transparent;";
            default ->
                    base + "-fx-background-color:" + C_CONTROL + ";-fx-text-fill:" + C_TEXT_SEC + ";" +
                            "-fx-border-color:#555;-fx-border-width:1;";
        };
    }

    private String btnStyleHover(String type) {
        String base = "-fx-font-family:'Segoe UI';-fx-font-size:12px;-fx-padding:7 14 7 14;" +
                "-fx-background-radius:4;-fx-border-radius:4;-fx-cursor:hand;";
        return switch (type) {
            case "primary", "accent" ->
                    base + "-fx-background-color:" + C_ACCENT_HOVER + ";-fx-text-fill:" + C_TEXT + ";-fx-border-color:transparent;";
            case "danger" ->
                    base + "-fx-background-color:" + C_DANGER_HOVER + ";-fx-text-fill:" + C_TEXT + ";-fx-border-color:transparent;";
            default ->
                    base + "-fx-background-color:" + C_CONTROL_H + ";-fx-text-fill:" + C_TEXT + ";" +
                            "-fx-border-color:#666;-fx-border-width:1;";
        };
    }

    private String btnStylePressed(String type) {
        String base = "-fx-font-family:'Segoe UI';-fx-font-size:12px;-fx-padding:7 14 7 14;" +
                "-fx-background-radius:4;-fx-border-radius:4;-fx-cursor:hand;";
        return switch (type) {
            case "primary", "accent" ->
                    base + "-fx-background-color:#005a9e;-fx-text-fill:" + C_TEXT + ";-fx-border-color:transparent;";
            case "danger" ->
                    base + "-fx-background-color:#8b1a12;-fx-text-fill:" + C_TEXT + ";-fx-border-color:transparent;";
            default ->
                    base + "-fx-background-color:#2d2d2d;-fx-text-fill:" + C_TEXT_SEC + ";" +
                            "-fx-border-color:#555;-fx-border-width:1;";
        };
    }

    private Label sectionHeader(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill:" + C_ACCENT + ";-fx-font-size:10px;-fx-font-weight:bold;" +
                "-fx-font-family:'Segoe UI';-fx-padding:2 0 2 0;");
        return lbl;
    }

    private Label fieldLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill:" + C_TEXT_TER + ";-fx-font-size:11px;-fx-font-family:'Segoe UI';");
        return lbl;
    }

    private Label metaLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill:" + C_TEXT_SEC + ";-fx-font-size:11px;-fx-font-family:'Segoe UI';");
        lbl.setWrapText(true);
        return lbl;
    }

    private Label styledPlaceholder(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill:" + C_TEXT_TER + ";-fx-font-size:13px;-fx-font-family:'Segoe UI';");
        return lbl;
    }

    private Region divider() {
        Region line = new Region();
        line.setPrefHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        line.setStyle("-fx-background-color:" + C_BORDER + ";");
        VBox.setMargin(line, new Insets(2, 0, 2, 0));
        return line;
    }

    private void styleTextField(TextField tf) {
        String base = "-fx-background-color:" + C_CONTROL + ";-fx-text-fill:" + C_TEXT + ";" +
                "-fx-prompt-text-fill:#666;-fx-border-color:#555;-fx-border-width:1;" +
                "-fx-border-radius:4;-fx-background-radius:4;-fx-padding:8 12 8 12;" +
                "-fx-font-size:13px;-fx-font-family:'Segoe UI';";
        tf.setStyle(base);
        tf.focusedProperty().addListener((obs, old, focused) ->
                tf.setStyle(base + (focused ? "-fx-border-color:" + C_ACCENT + ";" : "")));
    }

    private void styleCombo(ComboBox<String> cb) {
        cb.setStyle(
                "-fx-background-color:" + C_CONTROL + ";-fx-border-color:#555;-fx-border-width:1;" +
                        "-fx-border-radius:4;-fx-background-radius:4;-fx-font-size:12px;-fx-font-family:'Segoe UI';"
        );
    }

    private void styleProgressBar(ProgressBar pb) {
        pb.setStyle(
                "-fx-accent:" + C_ACCENT + ";-fx-background-color:" + C_BORDER + ";" +
                        "-fx-background-radius:2;-fx-background-insets:0;"
        );
    }

    private void applyGlobalStyles(Scene scene) {
        String css = """
            .root { -fx-font-family: 'Segoe UI'; }

            .table-view {
                -fx-background-color: #252526;
                -fx-border-color: #3d3d3d;
                -fx-border-width: 1;
                -fx-border-radius: 6;
                -fx-background-radius: 6;
                -fx-table-cell-border-color: transparent;
            }
            .table-view .filler,
            .table-view .column-header-background {
                -fx-background-color: #2a2a2a;
                -fx-border-color: transparent transparent #3d3d3d transparent;
                -fx-border-width: 0 0 1 0;
            }
            .table-view .column-header {
                -fx-background-color: transparent;
                -fx-border-color: transparent #3d3d3d transparent transparent;
                -fx-border-width: 0 1 0 0;
            }
            .table-view .column-header .label {
                -fx-text-fill: #888888;
                -fx-font-size: 11px;
                -fx-font-weight: bold;
                -fx-alignment: CENTER_LEFT;
                -fx-padding: 8 8 8 8;
            }
            .table-row-cell {
                -fx-background-color: transparent;
                -fx-border-color: transparent transparent #2d2d2d transparent;
                -fx-border-width: 0 0 1 0;
                -fx-cell-size: 32px;
            }
            .table-row-cell:odd  { -fx-background-color: rgba(255,255,255,0.02); }
            .table-row-cell:even { -fx-background-color: transparent; }
            .table-row-cell:hover { -fx-background-color: rgba(255,255,255,0.06); -fx-cursor: hand; }
            .table-row-cell:selected,
            .table-row-cell:selected:odd,
            .table-row-cell:selected:even { -fx-background-color: #003d6b; }
            .table-cell {
                -fx-text-fill: #cccccc;
                -fx-font-size: 12px;
                -fx-padding: 0 8 0 8;
                -fx-alignment: CENTER_LEFT;
                -fx-border-color: transparent;
            }
            .table-row-cell:selected .table-cell { -fx-text-fill: #ffffff; }
            .table-view .placeholder .label { -fx-text-fill: #555555; -fx-font-size: 13px; }

            .scroll-bar { -fx-background-color: transparent; -fx-padding: 0; }
            .scroll-bar .thumb { -fx-background-color: #4a4a4a; -fx-background-radius: 3; -fx-background-insets: 2; }
            .scroll-bar .thumb:hover { -fx-background-color: #666666; }
            .scroll-bar .track { -fx-background-color: transparent; }
            .scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-background-color: transparent; -fx-padding: 2; }
            .scroll-bar .increment-arrow, .scroll-bar .decrement-arrow { -fx-background-color: #555; -fx-shape: " "; -fx-padding: 2; }
            .scroll-pane { -fx-background-color: transparent; }
            .scroll-pane .viewport { -fx-background-color: transparent; }

            .combo-box .list-cell { -fx-text-fill: #cccccc; -fx-background-color: transparent; -fx-font-size: 12px; }
            .combo-box-popup .list-view { -fx-background-color: #3a3a3a; -fx-border-color: #555; -fx-border-width: 1; -fx-background-radius: 4; }
            .combo-box-popup .list-cell { -fx-text-fill: #cccccc; -fx-font-size: 12px; -fx-padding: 6 12 6 12; }
            .combo-box-popup .list-cell:hover   { -fx-background-color: #4d4d4d; }
            .combo-box-popup .list-cell:selected { -fx-background-color: #0078d4; -fx-text-fill: white; }
            .combo-box .arrow-button { -fx-background-color: transparent; }
            .combo-box .arrow        { -fx-background-color: #888; }

            .progress-bar > .track { -fx-background-color: #3d3d3d; -fx-background-radius: 2; }
            .progress-bar > .bar   { -fx-background-color: #0078d4; -fx-background-radius: 2; -fx-background-insets: 0; }
            .progress-bar:indeterminate > .bar { -fx-background-color: linear-gradient(to right, transparent, #0078d4, transparent); }

            /* Кнопка «Остановить» в задизабленном состоянии */
            .button:disabled { -fx-opacity: 0.35; }

            .tooltip { -fx-background-color: #3a3a3a; -fx-text-fill: #cccccc; -fx-border-color: #555; -fx-border-width: 1; -fx-background-radius: 4; -fx-font-size: 11px; }
            .dialog-pane { -fx-background-color: #2d2d2d; }
            .dialog-pane .content.label { -fx-text-fill: #cccccc; }
            .dialog-pane .header-panel  { -fx-background-color: #252526; }
            .dialog-pane .header-panel .label { -fx-text-fill: #ffffff; }
            """;

        try {
            Path tmp = Files.createTempFile("hdd-search-", ".css");
            Files.writeString(tmp, css);
            tmp.toFile().deleteOnExit();
            scene.getStylesheets().add(tmp.toUri().toString());
        } catch (IOException ex) {
            logger.warn("Не удалось применить глобальные стили: {}", ex.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Утилиты (без изменений)
    // ════════════════════════════════════════════════════════════════════════

    private List<String> getSystemRoots() {
        List<String> roots = new ArrayList<>();
        for (File root : File.listRoots()) {
            roots.add(root.getAbsolutePath());
        }
        return roots;
    }

    private List<Path> collectUniqueResultPaths() {
        Set<String> unique = new LinkedHashSet<>();
        for (FileResult result : nameResults)    unique.add(result.getPath());
        for (FileResult result : contentResults) unique.add(result.getPath());
        List<Path> paths = new ArrayList<>();
        for (String path : unique) paths.add(Path.of(path));
        return paths;
    }

    private TableView<FileResult> createTable() {
        TableView<FileResult> table = new TableView<>();

        TableColumn<FileResult, String> colName = new TableColumn<>("Имя файла");
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colName.setPrefWidth(220);

        TableColumn<FileResult, String> colPath = new TableColumn<>("Полный путь");
        colPath.setCellValueFactory(new PropertyValueFactory<>("path"));
        colPath.setPrefWidth(500);

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
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null) {
                String keyword = searchField.getText();
                String path    = newSel.getPath();

                new Thread(() -> {
                    String htmlSnippets;
                    try (SearchService svc = new SearchService(config, indexRegistry.allReadyIndexPaths())) {
                        htmlSnippets = svc.getHighlights(path, keyword);
                    }
                    Platform.runLater(() -> preview.getEngine().loadContent(
                            "<html><head><style>" +
                                    "body{background:#1e1e1e;color:#cccccc;font-family:'Segoe UI',sans-serif;font-size:13px;padding:12px;margin:0;}" +
                                    "h3{color:#0078d4;font-size:12px;text-transform:uppercase;letter-spacing:.5px;border-bottom:1px solid #3d3d3d;padding-bottom:6px;}" +
                                    "b,em{color:#f0c040;font-style:normal;font-weight:bold;}" +
                                    "p{line-height:1.6;margin:6px 0;}" +
                                    "::-webkit-scrollbar{width:6px;}" +
                                    "::-webkit-scrollbar-track{background:#1e1e1e;}" +
                                    "::-webkit-scrollbar-thumb{background:#4a4a4a;border-radius:3px;}" +
                                    "</style></head><body>" +
                                    "<h3>Фрагменты из файла</h3>" + htmlSnippets + "</body></html>"
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
                try { return Files.size(file); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) { return 0; }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { throw new RuntimeException(e); }
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
        indexingCancelled.set(true);
        backgroundExecutor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
