package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.control.*;
import javafx.scene.control.SplitPane;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Popup;
import org.example.photo.PhotoPdfResult;
import org.example.photo.PhotoPdfService;
import org.example.config.SearchConfig;
import org.example.export.ConflictStrategy;
import org.example.watcher.FileWatcherService;
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
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(MainApp.class);

    private final SearchConfig config = SearchConfig.load();
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final IndexRegistry indexRegistry = new IndexRegistry(config.getIndexPath());
    private final SearchHistoryService searchHistory =
            new SearchHistoryService(config.getIndexPath());

    private String hddPath = "";

    private final ObservableList<FileResult> nameResults = FXCollections.observableArrayList();
    private final ObservableList<FileResult> contentResults = FXCollections.observableArrayList();

    /** Флаг отмены текущей индексации. Устанавливается в true кнопкой «Стоп». */
    private final AtomicBoolean indexingCancelled = new AtomicBoolean(false);

    /** Флаг OCR — включает распознавание текста на изображениях и скан-PDF. */
    private final AtomicBoolean ocrEnabled = new AtomicBoolean(false);

    /** Ссылка на активный индексатор для корректной остановки по Esc/кнопке. */
    private final AtomicReference<IndexerService> activeIndexer = new AtomicReference<>();

    // ─── Пагинация результатов ────────────────────────────────────────────────
    /** ScoreDoc последней загруженной страницы «Совпадения в названии» для searchAfter. */
    private final AtomicReference<org.apache.lucene.search.ScoreDoc> lastNameDoc     = new AtomicReference<>(null);
    /** ScoreDoc последней загруженной страницы «Совпадения в содержимом». */
    private final AtomicReference<org.apache.lucene.search.ScoreDoc> lastContentDoc  = new AtomicReference<>(null);
    /** Полное число совпадений в названии (для подписи вкладки «N из M»). */
    private final AtomicReference<Long> totalNameHits    = new AtomicReference<>(0L);
    /** Полное число совпадений в содержимом. */
    private final AtomicReference<Long> totalContentHits = new AtomicReference<>(0L);

    // ─── Предпросмотр ─────────────────────────────────────────────────────────
    /**
     * Активный Future предпросмотра. Перед запуском нового — отменяем старый,
     * чтобы не плодить потоки и не показывать предпросмотр «не того» файла.
     */
    private final AtomicReference<java.util.concurrent.Future<?>> previewFuture = new AtomicReference<>(null);

    // ─── File Watcher ─────────────────────────────────────────────────────────
    /** Активный File Watcher для текущего hddPath. */
    private final AtomicReference<FileWatcherService> activeWatcher = new AtomicReference<>();
    /** Метка статуса индекса в сайдбаре: 🟢 / 🟡 */
    private Label lblWatcherStatus;

    // ─── UI-компоненты пагинации (инициализируются в start()) ────────────────
    private Label  lblNameCount;
    private Label  lblContentCount;
    private Button btnLoadMoreName;
    private Button btnLoadMoreContent;


    // ─── Fluent Design colour tokens ────────────────────────────────────────
    private static final String C_BG          = "#121417";
    private static final String C_SURFACE      = "#1E2329";
    private static final String C_PANEL        = "#1B1F24";
    private static final String C_BORDER       = "#242A31";
    private static final String C_ACCENT       = "#3B82F6";
    private static final String C_ACCENT_HOVER = "#2563EB";
    private static final String C_TEXT         = "#E6EAF0";
    private static final String C_TEXT_SEC     = "#9BA3AF";
    private static final String C_TEXT_TER     = "#6B7280";
    private static final String C_CONTROL      = "#1E2329";
    private static final String C_CONTROL_H    = "#242A31";
    private static final String C_DANGER       = "#EF4444";
    private static final String C_DANGER_HOVER = "#DC2626";

    // ─── Активная тема (live-switch) ──────────────────────────────────────────
    /** Хранит имя текущей темы: "jetbrains" | "win11". Volatile для read из FX-потока. */
    private volatile String currentTheme;
    /** Ссылка на корневую сцену для live-переключения темы. */
    private Scene mainScene;

    @Override
    public void start(Stage primaryStage) {
        logger.info("Запуск приложения. Lucene: {}", config.getLuceneVersion());
        currentTheme = config.getTheme();

        String bundledTesseract = org.example.tika.TikaService.resolveTesseractPath();
        if (bundledTesseract != null) {
            org.example.tika.TikaService.injectIntoPath(bundledTesseract);
            logger.info("Tesseract бандл добавлен в PATH: {}", bundledTesseract);
        }

        primaryStage.setTitle("HDD Search Engine");

        TableView<FileResult> nameTable = createTable();
        nameTable.setItems(nameResults);
        nameTable.setPlaceholder(styledPlaceholder("Нет результатов — выполните поиск"));

        TableView<FileResult> contentTable = createTable();
        contentTable.setItems(contentResults);
        contentTable.setPlaceholder(styledPlaceholder("Нет результатов — выполните поиск"));

        Label hddLabel = metaLabel("Источник не выбран");
        Label indexSizeLabel = metaLabel("Размер: —");
        Label indexStatusLabel = metaLabel("Статус: —");
        Label statusLabel = new Label("Готов к работе");
        statusLabel.getStyleClass().add("status-label");

        ComboBox<String> disksCombo = new ComboBox<>();
        disksCombo.getItems().addAll(getSystemRoots());
        if (!disksCombo.getItems().isEmpty()) {
            disksCombo.getSelectionModel().selectFirst();
            hddPath = disksCombo.getValue();
            hddLabel.setText(hddPath);
            refreshIndexInfo(indexSizeLabel, indexStatusLabel, hddPath);
        }
        disksCombo.setMaxWidth(Double.MAX_VALUE);
        styleCombo(disksCombo);

        Button btnChooseDirectory = fluentButton("📁", "secondary");
        btnChooseDirectory.setTooltip(new Tooltip("Выбрать директорию"));
        btnChooseDirectory.setPrefWidth(40);
        Button btnIndex = fluentButton("Индексировать", "primary");
        Button btnReindex = fluentButton("Переиндексировать", "secondary");
        Button btnDeleteIndex = fluentButton("Удалить индекс", "danger");
        Button btnExport = fluentButton("Экспорт файлов", "secondary");
        Button btnPhotoPdf = fluentButton("Фото → PDF", "secondary");

        // ── OCR ──────────────────────────────────────────────────────────────
        CheckBox chkOcr = new CheckBox("OCR (Tesseract)");
        chkOcr.setStyle("-fx-text-fill:" + C_TEXT_SEC + ";-fx-font-size:12px;-fx-font-family:'Segoe UI';");
        chkOcr.setTooltip(new Tooltip(
                "Включает распознавание текста на изображениях (JPG, PNG, TIFF)\n" +
                        "и отсканированных PDF без текстового слоя.\n" +
                        "Требует установленного Tesseract OCR."));

        // Метка статуса Tesseract — проверяется один раз при старте в фоне
        Label lblTesseract = metaLabel("Tesseract: проверка...");
        backgroundExecutor.execute(() -> {
            boolean available = org.example.tika.TikaService.isTesseractAvailable();
            Platform.runLater(() -> {
                if (available) {
                    lblTesseract.setText("Tesseract: ✓ найден  ·  OCR включён");
                    lblTesseract.setStyle("-fx-text-fill:#4caf50;-fx-font-size:11px;-fx-font-family:'Segoe UI';");
                    chkOcr.setDisable(false);
                    // Автоматически включаем OCR — пользователю ничего делать не нужно
                    chkOcr.setSelected(true);
                    ocrEnabled.set(true);
                } else {
                    lblTesseract.setText("Tesseract: не установлен  ·  OCR недоступен");
                    lblTesseract.setStyle("-fx-text-fill:" + C_TEXT_TER + ";-fx-font-size:11px;-fx-font-family:'Segoe UI';");
                    chkOcr.setDisable(true);
                    chkOcr.setTooltip(new Tooltip(
                            "Tesseract не найден в PATH.\n" +
                                    "Установите: https://github.com/tesseract-ocr/tesseract\n" +
                                    "Языки RU+EN: скачайте rus.traineddata и eng.traineddata\n" +
                                    "в папку tessdata."));
                }
            });
        });
        chkOcr.setOnAction(e -> ocrEnabled.set(chkOcr.isSelected()));

        ProgressBar pb = new ProgressBar(0);
        pb.setMaxWidth(Double.MAX_VALUE);
        pb.setPrefHeight(3);
        pb.setVisible(false);
        pb.setManaged(false);
        styleProgressBar(pb);

        TextField searchField = new TextField();
        searchField.setPromptText("Поиск...  (\"фраза\" для точного совпадения)");
        styleTextField(searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // ── История поиска: выпадающий список ─────────────────────────────────
        ListView<String> historyList = new ListView<>();
        historyList.setFocusTraversable(false);
        historyList.setStyle(
                "-fx-background-color:" + C_SURFACE + ";"
                        + "-fx-border-color:" + C_BORDER + ";-fx-border-width:1;"
                        + "-fx-font-family:'Segoe UI';-fx-font-size:13px;");
        historyList.setFixedCellSize(30);

        Popup historyPopup = new Popup();
        historyPopup.setAutoHide(true);
        historyPopup.setConsumeAutoHidingEvents(false);
        historyPopup.getContent().add(historyList);

        Runnable refreshHistoryPopup = () -> {
            String typed = searchField.getText();
            java.util.List<String> matches = searchHistory.filter(typed);
            if (matches.isEmpty()) { historyPopup.hide(); return; }
            historyList.getItems().setAll(matches);
            int rows = Math.min(matches.size(), 8);
            historyList.setPrefHeight(rows * 30 + 2);
            historyList.setPrefWidth(searchField.getWidth() > 0 ? searchField.getWidth() : 420);
            if (!historyPopup.isShowing()) {
                javafx.geometry.Bounds b = searchField.localToScreen(searchField.getBoundsInLocal());
                if (b != null) historyPopup.show(searchField, b.getMinX(), b.getMaxY() + 2);
            }
        };

        searchField.focusedProperty().addListener((obs, wasF, isF) -> {
            if (isF) refreshHistoryPopup.run();
            else historyPopup.hide();
        });
        searchField.textProperty().addListener((obs, oldT, newT) -> {
            if (searchField.isFocused()) refreshHistoryPopup.run();
        });
        historyList.setOnMouseClicked(e -> {
            String sel = historyList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                historyPopup.hide();
                searchField.setText(sel);
                performSearch(sel);
            }
        });

        Button btnSearch = fluentButton("Найти", "accent");
        Button btnSettings = fluentButton("⚙", "secondary");
        btnSettings.setPrefWidth(44);
        btnSettings.setOnAction(e -> showSettingsDialog(primaryStage));

        Label indexDot = new Label("●");
        indexDot.getStyleClass().add("index-dot");

        WebView previewArea = new WebView();
        previewArea.getStyleClass().add("preview-web");
        Label previewTitle = new Label("Файл не выбран");
        previewTitle.getStyleClass().add("preview-title");
        Label previewPlaceholder = new Label("Выберите файл в таблице для предпросмотра.");
        previewPlaceholder.getStyleClass().add("meta-muted");
        previewArea.getEngine().loadContent("<html><body style='background:#121417;color:#9BA3AF;font-family:Segoe UI;padding:12px;'>Предпросмотр недоступен: файл не выбран.</body></html>");

        Button btnOpenFile = fluentButton("Открыть", "primary");
        Button btnOpenFolder = fluentButton("Открыть папку", "secondary");
        btnOpenFile.setDisable(true);
        btnOpenFolder.setDisable(true);

        final FileResult[] selectedPreview = new FileResult[1];
        btnOpenFile.setOnAction(e -> {
            if (selectedPreview[0] != null) {
                openFile(selectedPreview[0].getPath());
            }
        });
        btnOpenFolder.setOnAction(e -> {
            if (selectedPreview[0] != null) {
                openFileLocation(selectedPreview[0].getPath());
            }
        });

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

        btnIndex.setOnAction(e -> {
            if ("Остановить".equals(btnIndex.getText())) {
                requestStopIndexing(btnIndex, statusLabel);
            } else {
                runIndexing(false, hddPath, btnIndex, btnReindex, pb, statusLabel, indexSizeLabel, indexStatusLabel);
            }
        });
        btnReindex.setOnAction(e ->
                runIndexing(true, hddPath, btnIndex, btnReindex, pb, statusLabel, indexSizeLabel, indexStatusLabel));

        btnDeleteIndex.setOnAction(e -> deleteIndexDialog(primaryStage, indexSizeLabel, indexStatusLabel, statusLabel));
        btnSearch.setOnAction(e -> performSearch(searchField.getText()));
        btnExport.setOnAction(e -> exportResults(primaryStage, statusLabel, pb, btnExport));
        btnPhotoPdf.setOnAction(e -> createPdfFromPhotos(primaryStage));

        setupSelectionListener(nameTable, searchField, previewArea, previewTitle, previewPlaceholder, selectedPreview, btnOpenFile, btnOpenFolder);
        setupSelectionListener(contentTable, searchField, previewArea, previewTitle, previewPlaceholder, selectedPreview, btnOpenFile, btnOpenFolder);

        // ── Кнопки «Загрузить ещё» и счётчики для каждой вкладки ────────────
        btnLoadMoreName    = fluentButton("Загрузить ещё…", "secondary");
        btnLoadMoreContent = fluentButton("Загрузить ещё…", "secondary");
        btnLoadMoreName.setVisible(false);
        btnLoadMoreContent.setVisible(false);

        lblNameCount    = new Label("");
        lblContentCount = new Label("");
        String countStyle = "-fx-text-fill:#6B7280;-fx-font-size:11px;-fx-font-family:'Segoe UI';";
        lblNameCount.setStyle(countStyle);
        lblContentCount.setStyle(countStyle);

        HBox nameFooter    = new HBox(10, lblNameCount,    btnLoadMoreName);
        HBox contentFooter = new HBox(10, lblContentCount, btnLoadMoreContent);
        nameFooter.setAlignment(Pos.CENTER_LEFT);
        contentFooter.setAlignment(Pos.CENTER_LEFT);
        nameFooter.setPadding(new Insets(4, 8, 4, 8));
        contentFooter.setPadding(new Insets(4, 8, 4, 8));

        VBox namePane    = new VBox(0, nameTable,    nameFooter);
        VBox contentPane = new VBox(0, contentTable, contentFooter);
        VBox.setVgrow(nameTable,    Priority.ALWAYS);
        VBox.setVgrow(contentTable, Priority.ALWAYS);

        btnLoadMoreName.setOnAction(e ->
                loadNextPage("filename", searchField.getText(), nameResults,
                        lastNameDoc, totalNameHits, lblNameCount, btnLoadMoreName));
        btnLoadMoreContent.setOnAction(e ->
                loadNextPage("content", searchField.getText(), contentResults,
                        lastContentDoc, totalContentHits, lblContentCount, btnLoadMoreContent));

        TabPane resultsTabs = new TabPane();
        resultsTabs.getStyleClass().add("results-tabs");
        Tab nameTab    = new Tab("Совпадения в названии",   namePane);
        Tab contentTab = new Tab("Совпадения в содержимом", contentPane);
        nameTab.setClosable(false);
        contentTab.setClosable(false);
        resultsTabs.getTabs().addAll(nameTab, contentTab);

        HBox topBar = new HBox(8, disksCombo, btnChooseDirectory, searchField, btnSearch, btnSettings, indexDot);
        topBar.getStyleClass().add("top-bar");
        topBar.setAlignment(Pos.CENTER_LEFT);

        VBox topBox = new VBox(topBar, pb);

        lblWatcherStatus = new Label("⚪ нет индекса");
        lblWatcherStatus.setStyle("-fx-text-fill:" + C_TEXT_TER + ";-fx-font-size:11px;-fx-font-family:'Segoe UI';");

        VBox indexPanel = new VBox(8,
                sectionHeader("ИНДЕКС"),
                hddLabel,
                indexStatusLabel,
                indexSizeLabel,
                lblWatcherStatus,
                btnIndex,
                btnReindex,
                btnDeleteIndex,
                divider(),
                sectionHeader("ДОПОЛНИТЕЛЬНО"),
                chkOcr,
                lblTesseract,
                btnExport,
                btnPhotoPdf,
                divider(),
                statusLabel
        );
        indexPanel.getStyleClass().add("left-panel");
        indexPanel.setPrefWidth(280);
        indexPanel.setMinWidth(240);

        HBox previewActions = new HBox(6, btnOpenFile, btnOpenFolder);
        VBox previewPane = new VBox(8, previewTitle, previewActions, previewPlaceholder, previewArea);
        previewPane.getStyleClass().add("preview-panel");
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        SplitPane centerSplit = new SplitPane(resultsTabs, previewPane);
        centerSplit.getStyleClass().add("content-split");
        centerSplit.setDividerPositions(0.65);

        BorderPane root = new BorderPane();
        root.setTop(topBox);
        root.setLeft(indexPanel);
        root.setCenter(centerSplit);
        root.getStyleClass().add("app-root");

        ChangeListener<Number> responsive = (obs, oldV, newV) -> {
            boolean small = newV.doubleValue() < 1200;
            previewPane.setManaged(!small);
            previewPane.setVisible(!small);
            if (small) {
                centerSplit.setDividerPositions(1.0);
            } else {
                centerSplit.setDividerPositions(0.65);
            }
        };
        primaryStage.widthProperty().addListener(responsive);

        Scene scene = new Scene(root, 1280, 820);
        mainScene = scene;
        configureAccelerators(scene, searchField, btnSearch, btnReindex, btnIndex, statusLabel, nameTable, contentTable);
        applyGlobalStyles(scene);
        // ── Иконка приложения ─────────────────────────────────────────────────────
        try {
            java.io.InputStream iconStream = getClass().getResourceAsStream("/icon.ico");
            if (iconStream == null) {
                logger.warn("Иконка не найдена в ресурсах: /icon.ico");
            } else {
                Image appIcon = new Image(iconStream);
                if (appIcon.isError()) {
                    logger.warn("Иконка загружена с ошибкой: {}", appIcon.getException().getMessage());
                } else {
                    primaryStage.getIcons().add(appIcon);
                    logger.info("Иконка приложения установлена.");
                }
            }
        } catch (Exception e) {
            logger.warn("Ошибка загрузки иконки: {}", e.getMessage());
        }
        primaryStage.setScene(scene);
        primaryStage.show();
        responsive.changed(primaryStage.widthProperty(), primaryStage.getWidth(), primaryStage.getWidth());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Диалог настроек
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Диалог ⚙️ настроек.
     * Отображает текущие значения из {@code config}, при сохранении пишет их
     * в {@link java.util.prefs.Preferences} через {@link SearchConfig#save}.
     * Изменения применяются при следующем запуске приложения
     * (т.к. config неизменяем после загрузки).
     */
    private void showSettingsDialog(Stage owner) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Настройки");
        dialog.setResizable(false);

        boolean isLight = "win11".equalsIgnoreCase(currentTheme);
        String dlgBg    = isLight ? "#F3F3F3"  : "#2d2d2d";
        String fieldStyle = isLight
                ? "-fx-background-color:#FFFFFF;-fx-text-fill:#1A1A1A;"
                + "-fx-border-color:#CACACA;-fx-border-width:1;-fx-border-radius:4;"
                + "-fx-background-radius:4;-fx-padding:6 10 6 10;"
                + "-fx-font-size:12px;-fx-font-family:'Segoe UI';"
                : "-fx-background-color:#3d3d3d;-fx-text-fill:#ffffff;"
                + "-fx-border-color:#555;-fx-border-width:1;-fx-border-radius:4;"
                + "-fx-background-radius:4;-fx-padding:6 10 6 10;"
                + "-fx-font-size:12px;-fx-font-family:'Segoe UI';";
        String labelStyle = isLight
                ? "-fx-text-fill:#1A1A1A;-fx-font-size:12px;-fx-font-family:'Segoe UI';"
                : "-fx-text-fill:#cccccc;-fx-font-size:12px;-fx-font-family:'Segoe UI';";
        String hintStyle = isLight
                ? "-fx-text-fill:#5A5A5A;-fx-font-size:10px;-fx-font-family:'Segoe UI';"
                : "-fx-text-fill:#666666;-fx-font-size:10px;-fx-font-family:'Segoe UI';";
        String dlgBtnStyle = "-fx-background-color:" + (isLight ? "#FDFDFD" : "#3d3d3d") + ";"
                + "-fx-text-fill:" + (isLight ? "#1A1A1A" : "#cccccc") + ";"
                + "-fx-border-color:" + (isLight ? "#D1D1D1" : "#555") + ";"
                + "-fx-border-width:1;-fx-border-radius:4;-fx-background-radius:4;"
                + "-fx-cursor:hand;-fx-padding:6 10 6 10;";


        // ── Папка индексов ────────────────────────────────────────────────────
        Label lblIndexPath = new Label("Папка индексов");
        lblIndexPath.setStyle(labelStyle);
        TextField tfIndexPath = new TextField(config.getIndexPath().toString());
        tfIndexPath.setStyle(fieldStyle);
        tfIndexPath.setPrefWidth(340);
        Button btnBrowse = new Button("📁");
        btnBrowse.setStyle(dlgBtnStyle);
        btnBrowse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Выберите папку для индексов");
            try { dc.setInitialDirectory(config.getIndexPath().toFile()); } catch (Exception ignored) {}
            File chosen = dc.showDialog(dialog);
            if (chosen != null) tfIndexPath.setText(chosen.getAbsolutePath());
        });
        HBox indexPathRow = new HBox(6, tfIndexPath, btnBrowse);
        indexPathRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tfIndexPath, Priority.ALWAYS);
        Label hintIndexPath = new Label("Папка где хранятся файлы индекса Lucene. Изменение вступит в силу при следующем запуске.");
        hintIndexPath.setStyle(hintStyle);
        hintIndexPath.setWrapText(true);

        // ── Потоки индексации ─────────────────────────────────────────────────
        Label lblThreads = new Label("Потоки индексации");
        lblThreads.setStyle(labelStyle);
        Spinner<Integer> spThreads = new Spinner<>(1, Runtime.getRuntime().availableProcessors() * 2,
                config.getIndexingThreads());
        spThreads.setEditable(true);
        spThreads.setPrefWidth(100);
        spThreads.setStyle("-fx-font-size:12px;");
        Label hintThreads = new Label("Рекомендуется: кол-во ядер ЦП (" +
                Runtime.getRuntime().availableProcessors() + "). Больше — быстрее для SSD, " +
                "но перегружает CPU при OCR.");
        hintThreads.setStyle(hintStyle);
        hintThreads.setWrapText(true);

        // ── RAM-буфер ─────────────────────────────────────────────────────────
        Label lblRam = new Label("RAM-буфер индексатора (МБ)");
        lblRam.setStyle(labelStyle);
        Spinner<Integer> spRam = new Spinner<>(16, 4096, (int) config.getRamBufferSizeMB(), 64);
        spRam.setEditable(true);
        spRam.setPrefWidth(100);
        spRam.setStyle("-fx-font-size:12px;");
        Label hintRam = new Label("Больше RAM → быстрее запись индекса, реже сбросы на диск. 256 МБ — оптимум.");
        hintRam.setStyle(hintStyle);
        hintRam.setWrapText(true);

        // ── Таймаут Tika ──────────────────────────────────────────────────────
        Label lblTimeout = new Label("Таймаут Tika (сек)");
        lblTimeout.setStyle(labelStyle);
        Spinner<Integer> spTimeout = new Spinner<>(1, 3600, config.getTikaTimeoutSeconds(), 10);
        spTimeout.setEditable(true);
        spTimeout.setPrefWidth(100);
        spTimeout.setStyle("-fx-font-size:12px;");
        Label hintTimeout = new Label("Максимальное время обработки одного файла. " +
                "Увеличьте до 120–300 сек если OCR не успевает обработать большие скан-PDF.");
        hintTimeout.setStyle(hintStyle);
        hintTimeout.setWrapText(true);

        // ── Макс. символов из файла ───────────────────────────────────────────
        Label lblMaxStr = new Label("Макс. символов из файла");
        lblMaxStr.setStyle(labelStyle);
        Spinner<Integer> spMaxStr = new Spinner<>(10_000, 5_000_000,
                config.getTikaMaxStringLength(), 50_000);
        spMaxStr.setEditable(true);
        spMaxStr.setPrefWidth(120);
        spMaxStr.setStyle("-fx-font-size:12px;");
        Label hintMaxStr = new Label("Tika обрежет текст файла до этого размера. " +
                "200 000 — оптимум. Увеличьте для очень больших документов.");
        hintMaxStr.setStyle(hintStyle);
        hintMaxStr.setWrapText(true);

        // ── Языки OCR ─────────────────────────────────────────────────────────
        Label lblOcr = new Label("Языки OCR (Tesseract)");
        lblOcr.setStyle(labelStyle);
        TextField tfOcr = new TextField(config.getOcrLanguage());
        tfOcr.setStyle(fieldStyle);
        tfOcr.setPrefWidth(200);
        Label hintOcr = new Label("Языки через '+': rus+eng, eng, deu+eng. " +
                "Языковые файлы .traineddata должны быть в папке tessdata.");
        hintOcr.setStyle(hintStyle);
        hintOcr.setWrapText(true);

        // ── Тема интерфейса ───────────────────────────────────────────────────
        Label lblTheme = new Label("Тема интерфейса");
        lblTheme.setStyle(labelStyle);

        ToggleGroup themeGroup = new ToggleGroup();
        RadioButton rbJetBrains = new RadioButton("JetBrains Dark");
        RadioButton rbWin11     = new RadioButton("Windows 11");
        rbJetBrains.setToggleGroup(themeGroup);
        rbWin11.setToggleGroup(themeGroup);
        String themeRadioStyle = isLight
                ? "-fx-text-fill:#1A1A1A;-fx-font-size:12px;-fx-font-family:'Segoe UI';"
                : "-fx-text-fill:#cccccc;-fx-font-size:12px;-fx-font-family:'Segoe UI';";
        rbJetBrains.setStyle(themeRadioStyle);
        rbWin11.setStyle(themeRadioStyle);
        if ("win11".equalsIgnoreCase(currentTheme)) rbWin11.setSelected(true);
        else rbJetBrains.setSelected(true);

        // Preview label
        Label lblThemePreview = new Label();
        lblThemePreview.setStyle("-fx-font-size:11px;-fx-font-family:'Segoe UI';-fx-font-style:italic;");
        Runnable updatePreview = () -> {
            if (rbWin11.isSelected())
                lblThemePreview.setText("Fluent Design · синий акцент #0078D4 · скруглённые элементы");
            else
                lblThemePreview.setText("Тёмная тема · синий акцент #3B82F6 · JetBrains стиль");
            lblThemePreview.setStyle("-fx-text-fill:#888;-fx-font-size:11px;-fx-font-family:'Segoe UI';-fx-font-style:italic;");
        };
        updatePreview.run();
        rbJetBrains.setOnAction(e -> updatePreview.run());
        rbWin11.setOnAction(e -> updatePreview.run());

        HBox themeRow = new HBox(16, rbJetBrains, rbWin11);
        themeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label hintTheme = new Label("Применяется мгновенно без перезапуска.");
        hintTheme.setStyle(hintStyle);

        // ── Папка для вложений ────────────────────────────────────────────────
        Label lblAttsPath = new Label("Папка для вложений");
        lblAttsPath.setStyle(labelStyle);
        TextField tfAttsPath = new TextField(config.getAttachmentsOutputPath());
        tfAttsPath.setStyle(fieldStyle);
        tfAttsPath.setPromptText("Пусто — спрашивать при каждом экспорте");
        tfAttsPath.setPrefWidth(340);
        Button btnBrowseAtts = new Button("📁");
        btnBrowseAtts.setStyle(dlgBtnStyle);
        btnBrowseAtts.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Выберите папку для сохранения вложений");
            String cur = tfAttsPath.getText().trim();
            if (!cur.isBlank()) {
                try { dc.setInitialDirectory(new File(cur)); } catch (Exception ignored) {}
            }
            File chosen = dc.showDialog(dialog);
            if (chosen != null) tfAttsPath.setText(chosen.getAbsolutePath());
        });
        Button btnClearAtts = new Button("✕");
        btnClearAtts.setStyle(dlgBtnStyle);
        btnClearAtts.setTooltip(new Tooltip("Сбросить (спрашивать каждый раз)"));
        btnClearAtts.setOnAction(e -> tfAttsPath.setText(""));
        HBox attsPathRow = new HBox(6, tfAttsPath, btnBrowseAtts, btnClearAtts);
        attsPathRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tfAttsPath, Priority.ALWAYS);
        Label hintAttsPath = new Label(
                "Папка по умолчанию для сохранения вложений из писем. " +
                        "Если не задана — директория будет выбираться каждый раз при экспорте.");
        hintAttsPath.setStyle(hintStyle);
        hintAttsPath.setWrapText(true);

        // ── Сборка формы ──────────────────────────────────────────────────────
        VBox form = new VBox(10,
                lblIndexPath, indexPathRow, hintIndexPath,
                separator(),
                lblThreads,  spThreads,  hintThreads,
                separator(),
                lblRam,      spRam,       hintRam,
                separator(),
                lblTimeout,  spTimeout,   hintTimeout,
                separator(),
                lblMaxStr,   spMaxStr,    hintMaxStr,
                separator(),
                lblOcr,      tfOcr,       hintOcr,
                separator(),
                lblTheme, themeRow, lblThemePreview, hintTheme,
                separator(),
                lblAttsPath, attsPathRow, hintAttsPath
        );
        form.setPadding(new Insets(20, 24, 8, 24));

        // ── Кнопки ────────────────────────────────────────────────────────────
        Button btnSave  = fluentButton("💾  Сохранить", "primary");
        Button btnReset = fluentButton("↺  Сбросить к умолчаниям", "secondary");
        Button btnCancel = fluentButton("Отмена", "secondary");

        btnSave.setPrefWidth(160);
        btnReset.setPrefWidth(200);
        btnCancel.setPrefWidth(100);

        // Подсказка «вступит в силу после перезапуска»
        Label lblRestartNote = new Label("⚠  Большинство изменений вступят в силу после перезапуска. Тема применяется мгновенно.");
        lblRestartNote.setStyle("-fx-text-fill:" + (isLight ? "#9E5A00" : "#f0c040") + ";-fx-font-size:11px;-fx-font-family:'Segoe UI';");
        lblRestartNote.setVisible(false);

        btnSave.setOnAction(e -> {
            // Валидация
            String indexPathVal = tfIndexPath.getText().trim();
            if (indexPathVal.isBlank()) {
                showAlertOnDialog(dialog, "Ошибка", "Папка индексов не может быть пустой.");
                return;
            }
            String ocrLang = tfOcr.getText().trim();
            if (ocrLang.isBlank()) {
                showAlertOnDialog(dialog, "Ошибка", "Укажите хотя бы один язык OCR (например: rus+eng).");
                return;
            }
            // Фиксируем значения Spinner (если редактировались вручную)
            spThreads.commitValue();
            spRam.commitValue();
            spTimeout.commitValue();
            spMaxStr.commitValue();

            String chosenTheme = rbWin11.isSelected() ? "win11" : "jetbrains";
            // Применяем тему немедленно, без перезапуска
            currentTheme = chosenTheme;
            if (mainScene != null) {
                applyTheme(mainScene, chosenTheme);
                // Обновляем и стили самого диалога настроек
                dialog.getScene().getStylesheets().clear();
                dialog.getScene().getStylesheets().addAll(mainScene.getStylesheets());
            }

            SearchConfig.save(
                    indexPathVal,
                    spRam.getValue().doubleValue(),
                    spThreads.getValue(),
                    spTimeout.getValue(),
                    spMaxStr.getValue(),
                    ocrLang,
                    tfAttsPath.getText().trim(),
                    chosenTheme
            );

            lblRestartNote.setVisible(true);
            btnSave.setDisable(true);
        });

        btnReset.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Сбросить все настройки к значениям по умолчанию?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Сброс настроек");
            confirm.initOwner(dialog);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES) {
                    SearchConfig.resetToDefaults();
                    // Обновляем поля в форме дефолтными значениями
                    tfIndexPath.setText("search-index");
                    spThreads.getValueFactory().setValue(SearchConfig.getDefaultIndexingThreads());
                    spRam.getValueFactory().setValue((int) SearchConfig.getDefaultRamBufferMB());
                    spTimeout.getValueFactory().setValue(SearchConfig.getDefaultTikaTimeout());
                    spMaxStr.getValueFactory().setValue(SearchConfig.getDefaultTikaMaxString());
                    tfOcr.setText(SearchConfig.getDefaultOcrLanguage());
                    tfAttsPath.setText("");
                    rbJetBrains.setSelected(true);
                    rbWin11.setSelected(false);
                    updatePreview.run();
                    lblRestartNote.setVisible(true);
                    btnSave.setDisable(false);
                }
            });
        });

        btnCancel.setOnAction(e -> dialog.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonsRow = new HBox(8, btnReset, spacer, btnCancel, btnSave);
        buttonsRow.setAlignment(Pos.CENTER_RIGHT);
        buttonsRow.setPadding(new Insets(12, 24, 16, 24));

        VBox root = new VBox(0, form, lblRestartNote, buttonsRow);
        VBox.setMargin(lblRestartNote, new Insets(8, 24, 0, 24));
        root.setStyle("-fx-background-color:" + dlgBg + ";");

        dialog.setScene(new Scene(root, 500, 720));
        dialog.getScene().getStylesheets().addAll(owner.getScene().getStylesheets());
        dialog.show();
    }

    /** Тонкий разделитель между секциями формы настроек. */
    private Region separator() {
        Region line = new Region();
        line.setPrefHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        line.setStyle("-fx-background-color:" + ("win11".equalsIgnoreCase(currentTheme) ? "#E0E0E0" : "#3a3a3a") + ";");
        VBox.setMargin(line, new Insets(2, 0, 2, 0));
        return line;
    }

    /** Alert с явным owner — чтобы всплывал поверх диалога, а не за ним. */
    private void showAlertOnDialog(Stage owner, String title, String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Удаление индекса
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
            if (answer.isEmpty() || answer.get() != ButtonType.YES) return;
            reindex = true;
        }

        boolean finalReindex = reindex;

        indexingCancelled.set(false);
        btnIndex.setText("Остановить");
        btnIndex.setStyle(btnStyle("danger"));
        btnReindex.setDisable(true);
        pb.setVisible(true);
        pb.setManaged(true);
        pb.setProgress(-1);
        statusLabel.setText("Подсчёт файлов...");
        indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));

        backgroundExecutor.execute(() -> {
            try {
                if (finalReindex && Files.exists(targetIndex)) {
                    deleteDirectory(targetIndex);
                }

                final long startMs = System.currentTimeMillis();

                IndexerService indexer = new IndexerService(config, targetIndex, ocrEnabled.get());
                activeIndexer.set(indexer);
                indexer.runIncrementalIndexing(
                        sourcePath,
                        (current, total, fileName) -> Platform.runLater(() -> {
                            if (total > 0) {
                                pb.setProgress((double) current / total);
                            }
                            String etaStr = formatEta(startMs, current, total);
                            String progressPct = total > 0
                                    ? String.format(" (%d%%)", (int) (100.0 * current / total))
                                    : "";

                            statusLabel.setText(String.format(
                                    "%,d / %,d файлов%s · %s · %s",
                                    current, total, progressPct, etaStr, fileName
                            ));
                        }),
                        indexingCancelled::get
                );

                long docs = indexer.countDocuments();
                long size = directorySize(targetIndex);
                indexRegistry.upsert(IndexRegistry.readyEntry(sourcePath, targetIndex, docs, size));

                long totalSec = (System.currentTimeMillis() - startMs) / 1000;
                boolean usedOcr = ocrEnabled.get();
                Platform.runLater(() -> {
                    pb.setProgress(1);
                    btnIndex.setText("Индексировать");
                    btnIndex.setStyle(btnStyle("primary"));
                    btnReindex.setDisable(false);
                    pb.setVisible(false);
                    pb.setManaged(false);
                    String ocrTag = usedOcr ? "  ·  OCR ✓" : "";
                    statusLabel.setText("✅ Готово за " + formatDuration(totalSec) + ocrTag + " · " + sourcePath);
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                    startFileWatcher(sourcePath, statusLabel);
                });

            } catch (CancellationException cancelled) {
                indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));
                Platform.runLater(() -> {
                    pb.setProgress(0);
                    btnIndex.setText("Индексировать");
                    btnIndex.setStyle(btnStyle("primary"));
                    btnReindex.setDisable(false);
                    pb.setVisible(false);
                    pb.setManaged(false);
                    statusLabel.setText("⏹ Индексация остановлена: " + sourcePath);
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                });

            } catch (Exception ex) {
                indexRegistry.upsert(IndexRegistry.failedEntry(sourcePath, targetIndex));
                Platform.runLater(() -> {
                    pb.setProgress(0);
                    btnIndex.setText("Индексировать");
                    btnIndex.setStyle(btnStyle("primary"));
                    btnReindex.setDisable(false);
                    pb.setVisible(false);
                    pb.setManaged(false);
                    statusLabel.setText("Ошибка индексации: " + ex.getMessage());
                    refreshIndexInfo(indexSizeLabel, indexStatusLabel, sourcePath);
                    showAlert("Ошибка", ex.getMessage());
                });
            } finally {
                activeIndexer.set(null);
            }
        });
    }


    // ════════════════════════════════════════════════════════════════════════
    //  File Watcher — автообновление индекса
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Запускает (или перезапускает) File Watcher для указанного пути.
     * Вызывается после успешной индексации в JavaFX-потоке.
     */
    private void startFileWatcher(String sourcePath, Label statusLabel) {
        FileWatcherService old = activeWatcher.getAndSet(null);
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }

        updateWatcherStatus(FileWatcherService.IndexStatus.UP_TO_DATE);

        backgroundExecutor.execute(() -> {
            try {
                FileWatcherService watcher = new FileWatcherService(
                        status -> Platform.runLater(() -> updateWatcherStatus(status)),
                        ()     -> runSilentReindex(sourcePath, statusLabel)
                );
                watcher.watchDirectory(java.nio.file.Path.of(sourcePath));
                watcher.start();
                activeWatcher.set(watcher);
                logger.info("FileWatcher запущен для: {}", sourcePath);
            } catch (Exception e) {
                logger.warn("Не удалось запустить FileWatcher для {}: {}", sourcePath, e.getMessage());
            }
        });
    }

    /**
     * Тихая фоновая переиндексация — не блокирует UI-кнопки.
     * Вызывается автоматически планировщиком FileWatcherService каждые 5 минут.
     */
    private void runSilentReindex(String sourcePath, Label statusLabel) {
        if (sourcePath == null || sourcePath.isBlank()) return;

        String indexDirName = org.example.index.IndexRegistry.buildIndexDirectoryName(sourcePath);
        java.nio.file.Path targetIndex = config.getIndexPath().resolve(indexDirName);

        Platform.runLater(() -> statusLabel.setText("🔄 Автообновление индекса: " + sourcePath + "..."));

        try {
            long startMs = System.currentTimeMillis();
            IndexerService indexer = new IndexerService(config, targetIndex, ocrEnabled.get());
            indexer.runIncrementalIndexing(sourcePath,
                    (current, total, fileName) -> {},   // прогресс не отображается (тихий режим)
                    indexingCancelled::get);

            long docs = indexer.countDocuments();
            long size = directorySize(targetIndex);
            indexRegistry.upsert(org.example.index.IndexRegistry.readyEntry(sourcePath, targetIndex, docs, size));

            long sec = (System.currentTimeMillis() - startMs) / 1000;
            Platform.runLater(() -> {
                statusLabel.setText("✅ Автообновление завершено за " + formatDuration(sec) + " · " + sourcePath);
                FileWatcherService w = activeWatcher.get();
                if (w != null) w.markUpToDate();
            });
        } catch (Exception e) {
            logger.warn("Ошибка автообновления индекса: {}", e.getMessage());
            Platform.runLater(() -> statusLabel.setText("⚠ Ошибка автообновления: " + e.getMessage()));
        }
    }

    /** Обновляет лейбл статуса индекса в сайдбаре. Должен вызываться из FX-потока. */
    private void updateWatcherStatus(FileWatcherService.IndexStatus status) {
        if (lblWatcherStatus == null) return;
        if (status == FileWatcherService.IndexStatus.UP_TO_DATE) {
            lblWatcherStatus.setText("🟢 актуален");
            lblWatcherStatus.setStyle("-fx-text-fill:#4CAF50;-fx-font-size:11px;-fx-font-family:'Segoe UI';-fx-font-weight:bold;");
        } else {
            lblWatcherStatus.setText("🟡 есть изменения");
            lblWatcherStatus.setStyle("-fx-text-fill:#FFC107;-fx-font-size:11px;-fx-font-family:'Segoe UI';-fx-font-weight:bold;");
        }
    }

    /**
     * Рассчитывает и форматирует оставшееся время.
     * Алгоритм: (elapsed / current) * (total - current)
     */
    private String formatEta(long startMs, int current, int total) {
        if (current <= 0 || total <= 0) return "считаем...";

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs < 1000) return "считаем...";

        long remaining = total - current;
        if (remaining <= 0) return "завершается...";

        long etaMs = (long)((double) elapsedMs / current * remaining);
        long etaSec = etaMs / 1000;

        return "~" + formatDuration(etaSec) + " осталось";
    }

    /** Форматирует секунды в "Xч Yмин Zсек" / "Yмин Zсек" / "Zсек" */
    private String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;

        if (h > 0) return String.format("%dч %02dмин", h, m);
        if (m > 0) return String.format("%dмин %02dсек", m, s);
        return String.format("%dсек", s);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Остальная бизнес-логика (без изменений)
    // ════════════════════════════════════════════════════════════════════════

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        searchHistory.add(query);

        // Сбрасываем результаты и состояние пагинации
        nameResults.clear();
        contentResults.clear();
        lastNameDoc.set(null);
        lastContentDoc.set(null);
        totalNameHits.set(0L);
        totalContentHits.set(0L);
        if (lblNameCount    != null) lblNameCount.setText("");
        if (lblContentCount != null) lblContentCount.setText("");
        if (btnLoadMoreName    != null) btnLoadMoreName.setVisible(false);
        if (btnLoadMoreContent != null) btnLoadMoreContent.setVisible(false);

        List<Path> indexPaths = indexRegistry.allReadyIndexPaths();
        if (indexPaths.isEmpty()) {
            showAlert("Поиск", "Нет готовых индексов. Сначала выполните индексацию диска.");
            return;
        }

        backgroundExecutor.execute(() -> {
            try (SearchService searcher = new SearchService(config, indexPaths)) {
                var namePage    = searcher.searchInFieldsPaged(query, "filename", null);
                var contentPage = searcher.searchInFieldsPaged(query, "content",  null);

                Platform.runLater(() -> {
                    // Название: первая страница
                    nameResults.addAll(namePage.results());
                    lastNameDoc.set(namePage.lastDoc());
                    totalNameHits.set(namePage.totalHits());
                    updatePageCounter(lblNameCount, btnLoadMoreName,
                            nameResults.size(), namePage.totalHits());

                    // Содержимое: первая страница
                    contentResults.addAll(contentPage.results());
                    lastContentDoc.set(contentPage.lastDoc());
                    totalContentHits.set(contentPage.totalHits());
                    updatePageCounter(lblContentCount, btnLoadMoreContent,
                            contentResults.size(), contentPage.totalHits());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showAlert("Ошибка поиска", ex.getMessage()));
            }
        });
    }

    /**
     * Загружает следующую страницу результатов через {@code searchAfter}.
     * Вызывается кнопкой «Загрузить ещё».
     */
    private void loadNextPage(String field,
                              String query,
                              ObservableList<FileResult> results,
                              AtomicReference<org.apache.lucene.search.ScoreDoc> lastDocRef,
                              AtomicReference<Long> totalHitsRef,
                              Label countLabel,
                              Button loadMoreBtn) {
        org.apache.lucene.search.ScoreDoc after = lastDocRef.get();
        if (after == null) return;  // уже загружено всё

        loadMoreBtn.setDisable(true);

        List<Path> indexPaths = indexRegistry.allReadyIndexPaths();
        if (indexPaths.isEmpty()) return;

        backgroundExecutor.execute(() -> {
            try (SearchService searcher = new SearchService(config, indexPaths)) {
                var page = searcher.searchInFieldsPaged(query, field, after);
                Platform.runLater(() -> {
                    results.addAll(page.results());
                    lastDocRef.set(page.lastDoc());
                    updatePageCounter(countLabel, loadMoreBtn,
                            results.size(), totalHitsRef.get());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    loadMoreBtn.setDisable(false);
                    showAlert("Ошибка загрузки", ex.getMessage());
                });
            }
        });
    }

    /**
     * Обновляет счётчик «Показано X из Y» и видимость кнопки «Загрузить ещё».
     */
    private void updatePageCounter(Label countLabel, Button loadMoreBtn,
                                   int shown, long total) {
        if (countLabel == null || loadMoreBtn == null) return;
        if (total == 0) {
            countLabel.setText("");
            loadMoreBtn.setVisible(false);
            return;
        }
        countLabel.setText("Показано " + shown + " из " + total);
        boolean hasMore = shown < total;
        loadMoreBtn.setVisible(hasMore);
        loadMoreBtn.setDisable(false);
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
            // Открываем в сохранённой папке вложений, если задана
            String savedAttsPath = config.getAttachmentsOutputPath();
            if (!savedAttsPath.isBlank()) {
                try {
                    File savedDir = new File(savedAttsPath);
                    if (savedDir.exists() && savedDir.isDirectory()) {
                        chooser.setInitialDirectory(savedDir);
                    }
                } catch (Exception ignored) {}
            }
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
        applyTheme(scene, currentTheme);
    }

    /**
     * Применяет (или переключает) тему для указанной сцены.
     * Безопасно вызывать повторно — предыдущие таблицы стилей очищаются.
     *
     * @param scene целевая сцена
     * @param theme имя темы: {@code "jetbrains"} или {@code "win11"}
     */
    private void applyTheme(Scene scene, String theme) {
        String cssFile = "win11".equalsIgnoreCase(theme) ? "/win11-theme.css" : "/jetbrains-theme.css";
        java.net.URL url = getClass().getResource(cssFile);
        if (url == null) {
            logger.warn("Не найден файл темы: {}", cssFile);
            return;
        }
        scene.getStylesheets().clear();
        scene.getStylesheets().add(url.toExternalForm());
        logger.info("Тема применена: {}", cssFile);
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

        TableColumn<FileResult, String> colIcon = new TableColumn<>("");
        colIcon.setCellValueFactory(new PropertyValueFactory<>("typeIcon"));
        colIcon.setPrefWidth(46);
        colIcon.setSortable(false);

        TableColumn<FileResult, String> colName = new TableColumn<>("Имя файла");
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colName.setPrefWidth(220);

        TableColumn<FileResult, String> colPath = new TableColumn<>("Полный путь");
        colPath.setCellValueFactory(new PropertyValueFactory<>("path"));
        colPath.setPrefWidth(460);

        TableColumn<FileResult, String> colSize = new TableColumn<>("Размер");
        colSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        colSize.setPrefWidth(120);

        TableColumn<FileResult, String> colModified = new TableColumn<>("Изменён");
        colModified.setCellValueFactory(new PropertyValueFactory<>("modified"));
        colModified.setPrefWidth(170);

        table.getColumns().addAll(colIcon, colName, colPath, colSize, colModified);

        table.setRowFactory(tv -> {
            TableRow<FileResult> row = new TableRow<>();

            MenuItem openItem = new MenuItem("Открыть");
            MenuItem openFolderItem = new MenuItem("Открыть папку с файлом");
            MenuItem copyPathItem = new MenuItem("Копировать путь");
            MenuItem copyNameItem = new MenuItem("Копировать имя файла");
            ContextMenu contextMenu = new ContextMenu(openItem, openFolderItem, new SeparatorMenuItem(), copyPathItem, copyNameItem);

            openItem.setOnAction(e -> {
                FileResult item = row.getItem();
                if (item != null) {
                    openFile(item.getPath());
                }
            });
            openFolderItem.setOnAction(e -> {
                FileResult item = row.getItem();
                if (item != null) {
                    openFileLocation(item.getPath());
                }
            });
            copyPathItem.setOnAction(e -> {
                FileResult item = row.getItem();
                if (item != null) {
                    copyToClipboard(item.getPath());
                }
            });
            copyNameItem.setOnAction(e -> {
                FileResult item = row.getItem();
                if (item != null) {
                    copyToClipboard(new File(item.getPath()).getName());
                }
            });

            row.itemProperty().addListener((obs, oldItem, newItem) -> {
                boolean hasItem = newItem != null;
                boolean exists = hasItem && Files.exists(Path.of(newItem.getPath()));
                openItem.setDisable(!exists);
                openFolderItem.setDisable(!exists);
                copyPathItem.setDisable(!hasItem);
                copyNameItem.setDisable(!hasItem);
            });

            row.emptyProperty().addListener((obs, wasEmpty, isNowEmpty) -> row.setContextMenu(isNowEmpty ? null : contextMenu));

            row.setOnContextMenuRequested(event -> {
                if (!row.isSelected()) {
                    event.consume();
                }
            });

            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    openFile(row.getItem().getPath());
                }
            });
            return row;
        });

        return table;
    }


    private void configureAccelerators(Scene scene,
                                       TextField searchField,
                                       Button btnSearch,
                                       Button btnReindex,
                                       Button btnIndex,
                                       Label statusLabel,
                                       TableView<FileResult> nameTable,
                                       TableView<FileResult> contentTable) {
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
                searchField::requestFocus
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ENTER),
                btnSearch::fire
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F5),
                btnReindex::fire
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN),
                () -> {
                    FileResult selected = getSelectedResult(nameTable, contentTable);
                    if (selected != null) {
                        copyToClipboard(selected.getPath());
                    }
                }
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                () -> requestStopIndexing(btnIndex, statusLabel)
        );
    }

    private FileResult getSelectedResult(TableView<FileResult> nameTable, TableView<FileResult> contentTable) {
        if (contentTable.isFocused() && contentTable.getSelectionModel().getSelectedItem() != null) {
            return contentTable.getSelectionModel().getSelectedItem();
        }
        if (nameTable.isFocused() && nameTable.getSelectionModel().getSelectedItem() != null) {
            return nameTable.getSelectionModel().getSelectedItem();
        }
        FileResult contentSelected = contentTable.getSelectionModel().getSelectedItem();
        if (contentSelected != null) {
            return contentSelected;
        }
        return nameTable.getSelectionModel().getSelectedItem();
    }

    private void requestStopIndexing(Button btnIndex, Label statusLabel) {
        indexingCancelled.set(true);
        IndexerService indexer = activeIndexer.get();
        if (indexer != null) {
            indexer.stop();
        }
        btnIndex.setDisable(true);
        statusLabel.setText("Остановка индексации...");
    }

    private void setupSelectionListener(TableView<FileResult> table,
                                        TextField searchField,
                                        WebView preview,
                                        Label previewTitle,
                                        Label previewPlaceholder,
                                        FileResult[] selectedPreview,
                                        Button btnOpenFile,
                                        Button btnOpenFolder) {
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null) {
                selectedPreview[0] = newSel;
                previewTitle.setText(newSel.getName());
                previewPlaceholder.setVisible(false);
                previewPlaceholder.setManaged(false);
                btnOpenFile.setDisable(false);
                btnOpenFolder.setDisable(false);

                String keyword = searchField.getText();
                String path = newSel.getPath();

                // Отменяем предыдущий предпросмотр — без этого при быстрых кликах
                // плодятся потоки и показывается предпросмотр «не того» файла
                java.util.concurrent.Future<?> prev = previewFuture.getAndSet(null);
                if (prev != null && !prev.isDone()) prev.cancel(true);

                // Показываем «загрузка» немедленно
                preview.getEngine().loadContent(
                        "<html><body style='background:#121417;color:#555;font-family:Segoe UI;" +
                                "padding:16px;'>Загрузка предпросмотра…</body></html>");

                java.util.concurrent.Future<?> future = backgroundExecutor.submit(() -> {
                    // Проверяем прерывание — если Future был отменён, не тратим время
                    if (Thread.currentThread().isInterrupted()) return;

                    String htmlSnippets;
                    try (SearchService svc = new SearchService(config, indexRegistry.allReadyIndexPaths())) {
                        htmlSnippets = svc.getHighlights(path, keyword);
                    } catch (Exception e) {
                        htmlSnippets = "Ошибка предпросмотра: " + e.getMessage();
                    }

                    if (Thread.currentThread().isInterrupted()) return;

                    final String finalHtml = htmlSnippets;
                    Platform.runLater(() -> preview.getEngine().loadContent(
                            "<html><head><style>" +
                                    "body{background:#121417;color:#E6EAF0;font-family:'Segoe UI',sans-serif;font-size:13px;padding:12px;margin:0;}" +
                                    "h3{color:#3B82F6;font-size:12px;text-transform:uppercase;letter-spacing:.5px;border-bottom:1px solid #2a3038;padding-bottom:6px;}" +
                                    "b,em{color:#F59E0B;font-style:normal;font-weight:bold;}" +
                                    "p{line-height:1.6;margin:6px 0;}" +
                                    "::-webkit-scrollbar{width:6px;}" +
                                    "::-webkit-scrollbar-track{background:#121417;}" +
                                    "::-webkit-scrollbar-thumb{background:#334155;border-radius:3px;}" +
                                    "</style></head><body>" +
                                    "<h3>Фрагменты из файла</h3>" + finalHtml + "</body></html>"
                    ));
                });
                previewFuture.set(future);
            }
        });
    }

    private void openFile(String path) {
        try {
            Desktop.getDesktop().open(new File(path));
        } catch (Exception e) {
            showAlert("Ошибка", "Не удалось открыть файл: " + e.getMessage());
        }
    }

    private void openFileLocation(String path) {
        File file = new File(path);
        if (!file.exists()) {
            showAlert("Ошибка", "Файл не найден: " + path);
            return;
        }

        try {
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                Runtime.getRuntime().exec("explorer.exe /select," + file.getAbsolutePath());
                return;
            }
            File parent = file.getParentFile();
            if (parent != null && parent.exists()) {
                Desktop.getDesktop().open(parent);
            }
        } catch (Exception e) {
            showAlert("Ошибка", "Не удалось открыть папку файла: " + e.getMessage());
        }
    }

    private void copyToClipboard(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
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
        FileWatcherService watcher = activeWatcher.getAndSet(null);
        if (watcher != null) {
            try { watcher.close(); } catch (Exception ignored) {}
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}