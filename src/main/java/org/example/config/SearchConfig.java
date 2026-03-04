package org.example.config;

import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.prefs.Preferences;

public final class SearchConfig {
    public static final String PROPERTIES_RESOURCE = "/search.properties";

    private static final Logger logger = LoggerFactory.getLogger(SearchConfig.class);

    private static final String KEY_INDEX_PATH               = "index.path";
    private static final String KEY_LUCENE_VERSION           = "lucene.version";
    private static final String KEY_RAM_BUFFER_MB            = "index.ramBufferSizeMB";
    private static final String KEY_INDEX_THREADS            = "index.threads";
    private static final String KEY_PHRASE_SLOP              = "search.phraseSlop";
    private static final String KEY_DEFAULT_OPERATOR         = "search.defaultOperator";
    private static final String KEY_TIKA_MAX_STRING          = "tika.maxStringLength";
    private static final String KEY_TIKA_TIMEOUT_SECONDS     = "tika.timeoutSeconds";
    private static final String KEY_OCR_LANGUAGE             = "ocr.language";

    private static final String DEFAULT_INDEX_PATH           = "search-index";
    private static final double DEFAULT_RAM_BUFFER_MB        = 256.0;
    private static final int    DEFAULT_INDEX_THREADS        = Runtime.getRuntime().availableProcessors();
    private static final int    DEFAULT_PHRASE_SLOP          = 2;
    private static final DefaultOperator DEFAULT_OPERATOR    = DefaultOperator.AND;
    private static final int    DEFAULT_TIKA_MAX_STRING      = 200_000;
    private static final int    DEFAULT_TIKA_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_OCR_LANGUAGE         = "rus+eng";

    private final Path   indexPath;
    private final String luceneVersion;
    private final double ramBufferSizeMB;
    private final int    indexingThreads;
    private final int    phraseSlop;
    private final DefaultOperator defaultOperator;
    private final int    tikaMaxStringLength;
    private final int    tikaTimeoutSeconds;
    private final String ocrLanguage;

    private SearchConfig(Path indexPath,
                         String luceneVersion,
                         double ramBufferSizeMB,
                         int indexingThreads,
                         int phraseSlop,
                         DefaultOperator defaultOperator,
                         int tikaMaxStringLength,
                         int tikaTimeoutSeconds,
                         String ocrLanguage) {
        this.indexPath           = indexPath;
        this.luceneVersion       = luceneVersion;
        this.ramBufferSizeMB     = ramBufferSizeMB;
        this.indexingThreads     = indexingThreads;
        this.phraseSlop          = phraseSlop;
        this.defaultOperator     = defaultOperator;
        this.tikaMaxStringLength = tikaMaxStringLength;
        this.tikaTimeoutSeconds  = tikaTimeoutSeconds;
        this.ocrLanguage         = ocrLanguage;
    }

    public static SearchConfig load() {
        Properties properties = new Properties();
        try (InputStream is = SearchConfig.class.getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (is != null) {
                properties.load(is);
            } else {
                logger.warn("Конфигурация {} не найдена. Используются значения по умолчанию.", PROPERTIES_RESOURCE);
            }
        } catch (IOException e) {
            logger.error("Ошибка чтения конфигурации {}. Используются значения по умолчанию.", PROPERTIES_RESOURCE, e);
        }

        Preferences prefs = Preferences.userNodeForPackage(SearchConfig.class);

        String indexPathValue = readValue(properties, prefs, KEY_INDEX_PATH).orElse(DEFAULT_INDEX_PATH);
        if (indexPathValue.isBlank()) {
            logger.warn("Параметр {} пустой. Используется: {}", KEY_INDEX_PATH, DEFAULT_INDEX_PATH);
            indexPathValue = DEFAULT_INDEX_PATH;
        }

        String luceneVersionValue = readValue(properties, prefs, KEY_LUCENE_VERSION)
                .filter(v -> !v.isBlank()).orElse(Version.LATEST.toString());

        double ramBufferValue  = readDouble(properties, prefs, KEY_RAM_BUFFER_MB,       DEFAULT_RAM_BUFFER_MB,        16, 4096);
        int    threadsValue    = readInt   (properties, prefs, KEY_INDEX_THREADS,        DEFAULT_INDEX_THREADS,         1,  256);
        int    slopValue       = readInt   (properties, prefs, KEY_PHRASE_SLOP,          DEFAULT_PHRASE_SLOP,           0,   20);
        var    opValue         = readOperator(properties, prefs, KEY_DEFAULT_OPERATOR,   DEFAULT_OPERATOR);
        int    tikaMaxValue    = readInt   (properties, prefs, KEY_TIKA_MAX_STRING,      DEFAULT_TIKA_MAX_STRING, 10_000, 5_000_000);
        int    tikaTimeoutVal  = readInt   (properties, prefs, KEY_TIKA_TIMEOUT_SECONDS, DEFAULT_TIKA_TIMEOUT_SECONDS,   1,  3600);
        String ocrLangValue    = readValue (properties, prefs, KEY_OCR_LANGUAGE)
                .filter(s -> !s.isBlank()).orElse(DEFAULT_OCR_LANGUAGE);

        return new SearchConfig(Paths.get(indexPathValue), luceneVersionValue,
                ramBufferValue, threadsValue, slopValue, opValue,
                tikaMaxValue, tikaTimeoutVal, ocrLangValue);
    }

    public static SearchConfig forTesting(Path indexPath) {
        return new SearchConfig(indexPath, Version.LATEST.toString(),
                DEFAULT_RAM_BUFFER_MB, 1, DEFAULT_PHRASE_SLOP, DEFAULT_OPERATOR,
                DEFAULT_TIKA_MAX_STRING, DEFAULT_TIKA_TIMEOUT_SECONDS, DEFAULT_OCR_LANGUAGE);
    }

    public static SearchConfig forTesting(Path indexPath,
                                          double ramBufferSizeMB, int indexingThreads,
                                          int phraseSlop, DefaultOperator defaultOperator,
                                          int tikaMaxStringLength, int tikaTimeoutSeconds) {
        return new SearchConfig(indexPath, Version.LATEST.toString(),
                ramBufferSizeMB, indexingThreads, phraseSlop, defaultOperator,
                tikaMaxStringLength, tikaTimeoutSeconds, DEFAULT_OCR_LANGUAGE);
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public Path   getIndexPath()            { return indexPath; }
    public String getLuceneVersion()        { return luceneVersion; }
    public double getRamBufferSizeMB()      { return ramBufferSizeMB; }
    public int    getIndexingThreads()      { return indexingThreads; }
    public int    getPhraseSlop()           { return phraseSlop; }
    public DefaultOperator getDefaultOperator() { return defaultOperator; }
    public int    getTikaMaxStringLength()  { return tikaMaxStringLength; }
    public int    getTikaTimeoutSeconds()   { return tikaTimeoutSeconds; }
    /** Языковые пакеты Tesseract, разделённые '+'. Пример: {@code "rus+eng"}. */
    public String getOcrLanguage()          { return ocrLanguage; }

    // ── Сохранение настроек ───────────────────────────────────────────────────

    /**
     * Сохраняет все параметры в {@link Preferences#userNodeForPackage}.
     * При следующем запуске {@link #load()} прочитает их с приоритетом над .properties.
     *
     * <p>Передавайте {@code null} чтобы сбросить конкретный параметр к значению
     * из .properties (или к дефолту). Передавайте пустую строку для строковых
     * полей не допускается — будет проигнорировано.</p>
     */
    public static void save(String indexPath,
                            double ramBufferMB,
                            int    indexingThreads,
                            int    tikaTimeoutSeconds,
                            int    tikaMaxStringLength,
                            String ocrLanguage) {
        Preferences prefs = Preferences.userNodeForPackage(SearchConfig.class);
        if (indexPath != null && !indexPath.isBlank()) {
            prefs.put(KEY_INDEX_PATH, indexPath);
        }
        prefs.putDouble(KEY_RAM_BUFFER_MB,        ramBufferMB);
        prefs.putInt   (KEY_INDEX_THREADS,         indexingThreads);
        prefs.putInt   (KEY_TIKA_TIMEOUT_SECONDS,  tikaTimeoutSeconds);
        prefs.putInt   (KEY_TIKA_MAX_STRING,       tikaMaxStringLength);
        if (ocrLanguage != null && !ocrLanguage.isBlank()) {
            prefs.put(KEY_OCR_LANGUAGE, ocrLanguage.trim());
        }
        try {
            prefs.flush();
            logger.info("Настройки сохранены в Preferences.");
        } catch (java.util.prefs.BackingStoreException e) {
            logger.warn("Не удалось записать Preferences на диск: {}", e.getMessage());
        }
    }

    /**
     * Сбрасывает все пользовательские настройки — при следующем запуске
     * будут применены значения из .properties (или дефолты).
     */
    public static void resetToDefaults() {
        Preferences prefs = Preferences.userNodeForPackage(SearchConfig.class);
        try {
            prefs.clear();
            prefs.flush();
            logger.info("Настройки сброшены к значениям по умолчанию.");
        } catch (java.util.prefs.BackingStoreException e) {
            logger.warn("Не удалось сбросить Preferences: {}", e.getMessage());
        }
    }

    // ── Публичные константы дефолтов (нужны для отображения в UI) ────────────

    public static int    getDefaultIndexingThreads()    { return DEFAULT_INDEX_THREADS; }
    public static double getDefaultRamBufferMB()        { return DEFAULT_RAM_BUFFER_MB; }
    public static int    getDefaultTikaTimeout()        { return DEFAULT_TIKA_TIMEOUT_SECONDS; }
    public static int    getDefaultTikaMaxString()      { return DEFAULT_TIKA_MAX_STRING; }
    public static String getDefaultOcrLanguage()        { return DEFAULT_OCR_LANGUAGE; }

    // ── Private helpers ───────────────────────────────────────────────────────
    private static Optional<String> readValue(Properties props, Preferences prefs, String key) {
        String pref = prefs.get(key, null);
        return pref != null ? Optional.of(pref) : Optional.ofNullable(props.getProperty(key));
    }

    private static int readInt(Properties props, Preferences prefs, String key,
                               int def, int min, int max) {
        String raw = readValue(props, prefs, key).orElse(null);
        if (raw == null) return def;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) { logger.warn("{}={} вне диапазона, используется {}", key, v, def); return def; }
            return v;
        } catch (NumberFormatException e) {
            logger.warn("{}={} не число, используется {}", key, raw, def);
            return def;
        }
    }

    private static double readDouble(Properties props, Preferences prefs, String key,
                                     double def, double min, double max) {
        String raw = readValue(props, prefs, key).orElse(null);
        if (raw == null) return def;
        try {
            double v = Double.parseDouble(raw.trim());
            if (v < min || v > max) { logger.warn("{}={} вне диапазона, используется {}", key, v, def); return def; }
            return v;
        } catch (NumberFormatException e) {
            logger.warn("{}={} не число, используется {}", key, raw, def);
            return def;
        }
    }

    private static DefaultOperator readOperator(Properties props, Preferences prefs,
                                                String key, DefaultOperator def) {
        String raw = readValue(props, prefs, key).orElse(null);
        if (raw == null) return def;
        try { return DefaultOperator.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { logger.warn("{}={} не распознан, используется {}", key, raw, def); return def; }
    }

    public enum DefaultOperator { AND, OR }
}
