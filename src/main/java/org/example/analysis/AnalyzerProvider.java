package org.example.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;

public final class AnalyzerProvider {
    /**
     * RussianAnalyzer является единственным источником истины для индексации, поиска и подсветки.
     * Его поведение основано на StandardTokenizer: пунктуация (включая "№") отбрасывается,
     * числа сохраняются как токены, регистр приводится к нижнему.
     */
    private static final Analyzer ANALYZER = new RussianAnalyzer();

    private AnalyzerProvider() {
    }

    public static Analyzer get() {
        return ANALYZER;
    }
}
