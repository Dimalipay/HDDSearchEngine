package org.example.watcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Следит за файловой системой через Java NIO {@link WatchService}.
 *
 * <ul>
 *   <li>Регистрирует директорию источника (рекурсивно, до {@value MAX_WATCH_DIRS} папок).</li>
 *   <li>Накапливает изменённые пути в {@code changedFiles}.</li>
 *   <li>Каждые {@value BATCH_INTERVAL_SEC} сек. (5 мин.) запускает фоновую переиндексацию.</li>
 *   <li>Передаёт статус {@link IndexStatus} слушателю через {@code statusListener}.</li>
 * </ul>
 *
 * <p><b>Ограничение:</b> для корневых дисков (C:\, D:\) рекурсивная регистрация
 * может занять несколько секунд — запускайте в фоновом потоке.</p>
 */
public class FileWatcherService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FileWatcherService.class);

    /** Максимальное число отслеживаемых директорий (защита от переполнения на больших дисках). */
    private static final int MAX_WATCH_DIRS = 15_000;

    /** Интервал пакетной переиндексации в секундах (5 минут). */
    static final long BATCH_INTERVAL_SEC = 300;

    // ── Статус индекса ────────────────────────────────────────────────────────

    public enum IndexStatus {
        /** Индекс актуален — изменений не обнаружено. */
        UP_TO_DATE,
        /** Накоплены изменения файловой системы; переиндексация ещё не выполнена. */
        HAS_CHANGES
    }

    // ── Внутренние поля ───────────────────────────────────────────────────────

    private final WatchService watchService;

    /** Фоновый поток опроса WatchService. */
    private final ExecutorService pollThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-watcher-poll");
        t.setDaemon(true);
        return t;
    });

    /** Планировщик пакетной переиндексации. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "file-watcher-batch");
        t.setDaemon(true);
        return t;
    });

    /** Ключи WatchService → соответствующий каталог. */
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();

    /** Накопленные изменённые пути (потокобезопасный Set). */
    private final Set<Path> changedFiles = ConcurrentHashMap.newKeySet();

    /** Текущий статус — читается из JavaFX-потока для обновления UI. */
    private volatile IndexStatus currentStatus = IndexStatus.UP_TO_DATE;

    /** Слушатель изменений статуса — вызывается из любого потока. */
    private final Consumer<IndexStatus> statusListener;

    /**
     * Callback переиндексации — вызывается из фонового потока планировщика
     * раз в {@value BATCH_INTERVAL_SEC} сек., если накоплены изменения.
     */
    private final Runnable reindexCallback;

    // ── Конструктор ───────────────────────────────────────────────────────────

    /**
     * @param statusListener  вызывается при смене статуса (из фонового потока)
     * @param reindexCallback вызывается для запуска переиндексации (из фонового потока)
     */
    public FileWatcherService(Consumer<IndexStatus> statusListener,
                              Runnable reindexCallback) throws IOException {
        this.statusListener  = Objects.requireNonNull(statusListener);
        this.reindexCallback = Objects.requireNonNull(reindexCallback);
        this.watchService    = FileSystems.getDefault().newWatchService();
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Рекурсивно регистрирует {@code root} и все вложенные папки.
     * Безопасно вызывать из фонового потока.
     *
     * @param root корневой каталог для наблюдения
     */
    public void watchDirectory(Path root) {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            logger.warn("FileWatcher: директория не существует или недоступна: {}", root);
            return;
        }

        AtomicInteger registered = new AtomicInteger(0);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (registered.get() >= MAX_WATCH_DIRS) {
                        logger.warn("FileWatcher: достигнут лимит {} директорий, остальные пропущены",
                                MAX_WATCH_DIRS);
                        return FileVisitResult.TERMINATE;
                    }
                    registerDir(dir);
                    registered.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Молча пропускаем недоступные папки (system dirs, etc.)
                    return FileVisitResult.CONTINUE;
                }
            });
            logger.info("FileWatcher: зарегистрировано {} директорий в «{}»",
                    registered.get(), root);
        } catch (IOException e) {
            logger.warn("FileWatcher: ошибка обхода «{}»: {}", root, e.getMessage());
        }
    }

    /**
     * Запускает фоновый поток опроса и планировщик пакетной переиндексации.
     * Вызывается один раз после {@link #watchDirectory}.
     */
    public void start() {
        pollThread.submit(this::pollLoop);
        scheduler.scheduleAtFixedRate(
                this::processBatch,
                BATCH_INTERVAL_SEC, BATCH_INTERVAL_SEC, TimeUnit.SECONDS
        );
        logger.info("FileWatcher запущен (интервал пакета: {} сек.)", BATCH_INTERVAL_SEC);
    }

    /** Возвращает текущий статус индекса. */
    public IndexStatus getStatus() {
        return currentStatus;
    }

    /**
     * Принудительно сбрасывает накопленные изменения и переводит статус в UP_TO_DATE.
     * Вызывается после успешной переиндексации извне.
     */
    public void markUpToDate() {
        changedFiles.clear();
        setStatus(IndexStatus.UP_TO_DATE);
    }

    @Override
    public void close() {
        logger.info("FileWatcher: остановка...");
        pollThread.shutdownNow();
        scheduler.shutdownNow();
        try {
            watchService.close();
        } catch (IOException e) {
            logger.warn("FileWatcher: ошибка закрытия WatchService: {}", e.getMessage());
        }
        keyToDir.clear();
        changedFiles.clear();
    }

    // ── Внутренние методы ────────────────────────────────────────────────────

    private void registerDir(Path dir) {
        try {
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            keyToDir.put(key, dir);
        } catch (IOException e) {
            // Пропускаем защищённые системные директории
            logger.debug("FileWatcher: не удалось зарегистрировать «{}»: {}", dir, e.getMessage());
        }
    }

    /** Основной цикл опроса WatchService — выполняется в {@code pollThread}. */
    private void pollLoop() {
        logger.debug("FileWatcher: цикл опроса запущен");
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                // Блокируем не более 1 сек., чтобы реагировать на interrupt
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
            if (key == null) continue;

            Path dir = keyToDir.get(key);
            if (dir == null) {
                key.cancel();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    // Буфер переполнился — помечаем саму директорию
                    changedFiles.add(dir);
                    continue;
                }

                @SuppressWarnings("unchecked")
                Path relative = ((WatchEvent<Path>) event).context();
                Path changed  = dir.resolve(relative);
                changedFiles.add(changed);

                // Если создана новая папка — регистрируем её тоже
                if (kind == StandardWatchEventKinds.ENTRY_CREATE
                        && Files.isDirectory(changed)
                        && keyToDir.size() < MAX_WATCH_DIRS) {
                    registerDir(changed);
                }
            }

            // Переводим в HAS_CHANGES при первом изменении
            if (!changedFiles.isEmpty() && currentStatus != IndexStatus.HAS_CHANGES) {
                setStatus(IndexStatus.HAS_CHANGES);
            }

            boolean valid = key.reset();
            if (!valid) {
                // Директория удалена — убираем ключ
                keyToDir.remove(key);
            }
        }
        logger.debug("FileWatcher: цикл опроса завершён");
    }

    /**
     * Вызывается планировщиком каждые {@value BATCH_INTERVAL_SEC} сек.
     * Если накоплены изменения — запускает переиндексацию.
     */
    private void processBatch() {
        if (changedFiles.isEmpty()) return;

        int count = changedFiles.size();
        changedFiles.clear();   // сбрасываем до переиндексации — новые изменения начнут накапливаться заново
        logger.info("FileWatcher: пакетная переиндексация — {} изменённых файлов", count);

        try {
            reindexCallback.run();
        } catch (Exception e) {
            logger.error("FileWatcher: ошибка в callback переиндексации: {}", e.getMessage(), e);
        }
        // Статус UP_TO_DATE выставляется в callback (MainApp.runSilentReindex) после завершения
    }

    private void setStatus(IndexStatus status) {
        currentStatus = status;
        try {
            statusListener.accept(status);
        } catch (Exception e) {
            logger.warn("FileWatcher: ошибка в statusListener: {}", e.getMessage());
        }
    }
}
