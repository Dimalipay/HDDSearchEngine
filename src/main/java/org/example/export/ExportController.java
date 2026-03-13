package org.example.export;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

public class ExportController {
    private final ExportService exportService;
    private final Executor executor;

    public ExportController(ExportService exportService, Executor executor) {
        this.exportService = exportService;
        this.executor = executor;
    }

    public void exportResults(Stage stage,
                              List<Path> files,
                              Label statusLabel,
                              ProgressBar progressBar,
                              Button btnExport,
                              BiConsumer<String, String> showAlert) {
        if (files.isEmpty()) {
            showAlert.accept("Экспорт", "Нет найденных файлов для экспорта.");
            return;
        }

        ChoiceDialog<String> modeDialog = new ChoiceDialog<>("В папку", "В папку", "В ZIP");
        modeDialog.setTitle("Экспорт");
        modeDialog.setHeaderText("Выберите формат экспорта");
        Optional<String> mode = modeDialog.showAndWait();
        if (mode.isEmpty()) return;

        btnExport.setDisable(true);
        progressBar.setProgress(0);

        if ("В папку".equals(mode.get())) {
            exportToFolder(stage, files, statusLabel, progressBar, btnExport, showAlert);
        } else {
            exportToZip(stage, files, statusLabel, progressBar, btnExport, showAlert);
        }
    }

    private void exportToFolder(Stage stage,
                                List<Path> files,
                                Label statusLabel,
                                ProgressBar progressBar,
                                Button btnExport,
                                BiConsumer<String, String> showAlert) {
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

        executor.execute(() -> {
            ExportResult result = exportService.exportToDirectory(files, targetDir.toPath(), strategy,
                    (processed, total) -> Platform.runLater(() -> {
                        progressBar.setProgress(total == 0 ? 1 : (double) processed / total);
                        statusLabel.setText(String.format("Экспорт в папку: %d/%d", processed, total));
                    }));
            Platform.runLater(() -> {
                btnExport.setDisable(false);
                statusLabel.setText(String.format("Экспорт завершен. Успешно: %d, пропущено: %d, ошибок: %d",
                        result.exported(), result.skipped(), result.failed()));
                showAlert.accept("Экспорт завершен", statusLabel.getText());
            });
        });
    }

    private void exportToZip(Stage stage,
                             List<Path> files,
                             Label statusLabel,
                             ProgressBar progressBar,
                             Button btnExport,
                             BiConsumer<String, String> showAlert) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Сохранить ZIP-архив");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP архив", "*.zip"));
        fileChooser.setInitialFileName("search-results.zip");
        File zipFile = fileChooser.showSaveDialog(stage);
        if (zipFile == null) {
            btnExport.setDisable(false);
            return;
        }

        executor.execute(() -> {
            ExportResult result = exportService.exportToZip(files, zipFile.toPath(),
                    (processed, total) -> Platform.runLater(() -> {
                        progressBar.setProgress(total == 0 ? 1 : (double) processed / total);
                        statusLabel.setText(String.format("Экспорт в ZIP: %d/%d", processed, total));
                    }));
            Platform.runLater(() -> {
                btnExport.setDisable(false);
                statusLabel.setText(String.format("ZIP экспорт завершен. Успешно: %d, ошибок: %d",
                        result.exported(), result.failed()));
                showAlert.accept("Экспорт завершен", statusLabel.getText());
            });
        });
    }
}
