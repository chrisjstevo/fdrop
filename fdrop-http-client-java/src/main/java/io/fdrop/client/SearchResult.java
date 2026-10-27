package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Search results; names and snippets are runs of text with the matching parts marked, never HTML. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchResult(long total, String nextCursor, List<Hit> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(String text, boolean match) {
    }

    /** {@code contents} says whether the file's text was searched: ok, unsupported, too_large, protected, failed, pending. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hit(String path, List<Segment> name, String location, String type, boolean folder, long size,
                      String modified, String expiresAt, List<List<Segment>> snippets, String contents) {
    }
}
