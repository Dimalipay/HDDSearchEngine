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

    private static final String KEY_INDEX_PATH = "index.path";
    private static final String KEY_LUCENE_VERSION = "lucene.version";
    private static final String KEY_RAM_BUFFER_MB = "index.ramBufferSizeMB";
    private static final String KEY_INDEX_THREADS = "index.threads";
    private static final String KEY_PHRASE_SLOP = "search.phraseSlop";
    private static final String KEY_DEFAULT_OPERATOR = "search.defaultOperator";
    private static final String KEY_TIKA_MAX_STRING = "tika.maxStringLength";
    private static final String KEY_TIKA_TIMEOUT_SECONDS = "tika.timeoutSeconds";

    private static final String DEFAULT_INDEX_PATH = "search-index";
    private static final double DEFAULT_RAM_BUFFER_MB = 256.0;
    private static final int DEFAULT_INDEX_THREADS = Runtime.getRuntime().availableProcessors();
    private static final int DEFAULT_PHRASE_SLOP = 2;
    private static final DefaultOperator DEFAULT_OPERATOR = DefaultOperator.AND;
    private static final int DEFAULT_TIKA_MAX_STRING = 200_000;
    private static final int DEFAULT_TIKA_TIMEOUT_SECONDS = 30;

    private final Path indexPath;
    private final String luceneVersion;
    private final double ramBufferSizeMB;
    private final int indexingThreads;
    private final int phraseSlop;
    private final DefaultOperator defaultOperator;
    private final int tikaMaxStringLength;
    private final int tikaTimeoutSeconds;

    private SearchConfig(Path indexPath,
                         String luceneVersion,
                         double ramBufferSizeMB,
                         int indexingThreads,
                         int phraseSlop,
                         DefaultOperator defaultOperator,
                         int tikaMaxStringLength,
                         int tikaTimeoutSeconds) {
        this.indexPath = indexPath;
        this.luceneVersion = luceneVersion;
        this.ramBufferSizeMB = ramBufferSizeMB;
        this.indexingThreads = indexingThreads;
        this.phraseSlop = phraseSlop;
        this.defaultOperator = defaultOperator;
        this.tikaMaxStringLength = tikaMaxStringLength;
        this.tikaTimeoutSeconds = tikaTimeoutSeconds;
    }

    public static SearchConfig load() {
        Properties properties = new Properties();
        try (InputStream inputStream = SearchConfig.class.getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (inputStream != null) {
                properties.load(inputStream);
            } else {
                logger.warn("Конфигурация {} не найдена. Используются значения по умолчанию.", PROPERTIES_RESOURCE);
            }
        } catch (IOException e) {
            logger.error("Ошибка чтения конфигурации {}. Используются значения по умолчанию.", PROPERTIES_RESOURCE, e);
        }

        Preferences prefs = Preferences.userNodeForPackage(SearchConfig.class);

        String indexPathValue = readValue(properties, prefs, KEY_INDEX_PATH).orElse(DEFAULT_INDEX_PATH);
        if (indexPathValue.isBlank()) {
            logger.warn("Параметр {} пустой. Используется значение по умолчанию: {}", KEY_INDEX_PATH, DEFAULT_INDEX_PATH);
            indexPathValue = DEFAULT_INDEX_PATH;
        }

        String luceneVersionValue = readValue(properties, prefs, KEY_LUCENE_VERSION)
                .filter(value -> !value.isBlank())
                .orElse(Version.LATEST.toString());

        double ramBufferValue = readDouble(properties, prefs, KEY_RAM_BUFFER_MB, DEFAULT_RAM_BUFFER_MB, 16, 4096);
        int indexThreadsValue = readInt(properties, prefs, KEY_INDEX_THREADS, DEFAULT_INDEX_THREADS, 1, 256);
        int phraseSlopValue = readInt(properties, prefs, KEY_PHRASE_SLOP, DEFAULT_PHRASE_SLOP, 0, 20);
        DefaultOperator operatorValue = readOperator(properties, prefs, KEY_DEFAULT_OPERATOR, DEFAULT_OPERATOR);
        int tikaMaxStringValue = readInt(properties, prefs, KEY_TIKA_MAX_STRING, DEFAULT_TIKA_MAX_STRING, 10_000, 5_000_000);
        int tikaTimeoutValue = readInt(properties, prefs, KEY_TIKA_TIMEOUT_SECONDS, DEFAULT_TIKA_TIMEOUT_SECONDS, 1, 3600);

        return new SearchConfig(
                Paths.get(indexPathValue),
                luceneVersionValue,
                ramBufferValue,
                indexThreadsValue,
                phraseSlopValue,
                operatorValue,
                tikaMaxStringValue,
                tikaTimeoutValue
        );
    }

    public static SearchConfig forTesting(Path indexPath) {
        return new SearchConfig(
                indexPath,
                Version.LATEST.toString(),
                DEFAULT_RAM_BUFFER_MB,
                1,
                DEFAULT_PHRASE_SLOP,
                DEFAULT_OPERATOR,
                DEFAULT_TIKA_MAX_STRING,
                DEFAULT_TIKA_TIMEOUT_SECONDS
        );
    }

    public static SearchConfig forTesting(Path indexPath,
                                          double ramBufferSizeMB,
                                          int indexingThreads,
                                          int phraseSlop,
                                          DefaultOperator defaultOperator,
                                          int tikaMaxStringLength,
                                          int tikaTimeoutSeconds) {
        return new SearchConfig(
                indexPath,
                Version.LATEST.toString(),
                ramBufferSizeMB,
                indexingThreads,
                phraseSlop,
                defaultOperator,
                tikaMaxStringLength,
                tikaTimeoutSeconds
        );
    }

    private static Optional<String> readValue(Properties props, Preferences prefs, String key) {
        String prefValue = prefs.get(key, null);
        if (prefValue != null) {
            return Optional.of(prefValue);
        }
        return Optional.ofNullable(props.getProperty(key));
    }

    private static int readInt(Properties props, Preferences prefs, String key, int defaultValue, int min, int max) {
        String raw = readValue(props, prefs, key).orElse(null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                logger.warn("Параметр {}={} вне диапазона {}-{}. Используется значение по умолчанию: {}",
                        key, value, min, max, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException e) {
            logger.warn("Параметр {}={} не является числом. Используется значение по умолчанию: {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static double readDouble(Properties props, Preferences prefs, String key, double defaultValue, double min, double max) {
        String raw = readValue(props, prefs, key).orElse(null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (value < min || value > max) {
                logger.warn("Параметр {}={} вне диапазона {}-{}. Используется значение по умолчанию: {}",
                        key, value, min, max, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException e) {
            logger.warn("Параметр {}={} не является числом. Используется значение по умолчанию: {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static DefaultOperator readOperator(Properties props, Preferences prefs, String key, DefaultOperator defaultValue) {
        String raw = readValue(props, prefs, key).orElse(null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return DefaultOperator.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Параметр {}={} не распознан. Используется значение по умолчанию: {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    public Path getIndexPath() {
        return indexPath;
    }

    public String getLuceneVersion() {
        return luceneVersion;
    }

    public double getRamBufferSizeMB() {
        return ramBufferSizeMB;
    }

    public int getIndexingThreads() {
        return indexingThreads;
    }

    public int getPhraseSlop() {
        return phraseSlop;
    }

    public DefaultOperator getDefaultOperator() {
        return defaultOperator;
    }

    public int getTikaMaxStringLength() {
        return tikaMaxStringLength;
    }

    public int getTikaTimeoutSeconds() {
        return tikaTimeoutSeconds;
    }

    public enum DefaultOperator {
        AND,
        OR
    }
}
