package io.fdrop.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * How text is split into searchable words. Contents use the standard analyzer with no stop words; names and paths
 * also split on {@code _ - .}, digits and camelCase ({@code nightly-2026-10-26.csv} → nightly, 2026, 10, 26, csv);
 * the name prefix field adds edge n-grams so the first letters of a name match while typing.
 */
final class Analyzers {

    static final String ID = "id";
    static final String AREA = "area";
    static final String REL = "rel";
    static final String PATH = "path";
    static final String NAME = "name";
    static final String NAME_PREFIX = "namePrefix";
    static final String CONTENT = "content";
    static final String TYPE = "type";
    static final String FOLDER = "folder";
    static final String SIZE = "size";
    static final String MODIFIED = "modified";
    static final String EXPIRES = "expiresAt";
    static final String STATE = "extractState";

    final Analyzer content = new StandardAnalyzer(CharArraySet.EMPTY_SET);
    final Analyzer name;
    final Analyzer namePrefix;
    final Analyzer perField;

    Analyzers() {
        try {
            this.name = words(false);
            this.namePrefix = words(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.perField = new PerFieldAnalyzerWrapper(content, Map.of(NAME, name, PATH, name, NAME_PREFIX, namePrefix));
    }

    private static Analyzer words(boolean prefixes) throws IOException {
        CustomAnalyzer.Builder b = CustomAnalyzer.builder()
                .withTokenizer("pattern", "pattern", "[^\\p{L}\\p{N}]+", "group", "-1")
                .addTokenFilter("wordDelimiterGraph", "splitOnCaseChange", "1", "splitOnNumerics", "1",
                        "generateWordParts", "1", "generateNumberParts", "1", "preserveOriginal", "1")
                .addTokenFilter("flattenGraph")
                .addTokenFilter("lowercase");
        if (prefixes) {
            b.addTokenFilter("edgeNGram", "minGramSize", "2", "maxGramSize", "20", "preserveOriginal", "true");
        }
        return b.build();
    }
}
