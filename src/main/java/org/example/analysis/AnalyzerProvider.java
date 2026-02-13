package org.example.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.ru.RussianAnalyzer;

import java.util.HashMap;
import java.util.Map;

public final class AnalyzerProvider {
    public static final String FIELD_FILENAME_RU = "filename_ru";
    public static final String FIELD_FILENAME_EN = "filename_en";
    public static final String FIELD_FILENAME_NO_EXT_RU = "filename_no_ext_ru";
    public static final String FIELD_FILENAME_NO_EXT_EN = "filename_no_ext_en";
    public static final String FIELD_CONTENT_RU = "content_ru";
    public static final String FIELD_CONTENT_EN = "content_en";

    private static final Analyzer RUSSIAN_ANALYZER = new RussianAnalyzer();
    private static final Analyzer ENGLISH_ANALYZER = new EnglishAnalyzer();
    private static final Analyzer MULTILINGUAL_ANALYZER = buildMultilingualAnalyzer();

    private AnalyzerProvider() {
    }

    public static Analyzer getRussianAnalyzer() {
        return RUSSIAN_ANALYZER;
    }

    public static Analyzer getEnglishAnalyzer() {
        return ENGLISH_ANALYZER;
    }

    public static Analyzer getMultilingualAnalyzer() {
        return MULTILINGUAL_ANALYZER;
    }

    private static Analyzer buildMultilingualAnalyzer() {
        Map<String, Analyzer> fieldAnalyzers = new HashMap<>();
        fieldAnalyzers.put(FIELD_FILENAME_RU, RUSSIAN_ANALYZER);
        fieldAnalyzers.put(FIELD_FILENAME_EN, ENGLISH_ANALYZER);
        fieldAnalyzers.put(FIELD_FILENAME_NO_EXT_RU, RUSSIAN_ANALYZER);
        fieldAnalyzers.put(FIELD_FILENAME_NO_EXT_EN, ENGLISH_ANALYZER);
        fieldAnalyzers.put(FIELD_CONTENT_RU, RUSSIAN_ANALYZER);
        fieldAnalyzers.put(FIELD_CONTENT_EN, ENGLISH_ANALYZER);
        return new PerFieldAnalyzerWrapper(RUSSIAN_ANALYZER, fieldAnalyzers);
    }
}
