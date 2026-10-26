package io.fdrop.core.search;

import io.fdrop.core.auth.Principal;

import java.util.List;
import java.util.Map;

/**
 * Full-text search over the names and contents of the files a user can open (see "Search" in SPEC.md). Implemented
 * by the fdrop-search module; without it, {@link NoSearch} answers that search is unavailable.
 */
public interface SearchService extends AutoCloseable {

    /**
     * A search. {@code scope} is {@code all} or {@code folder} (then {@code path} names the folder); {@code type} a
     * type family; {@code sort} {@code relevance} or {@code newest}; {@code cursor} from the previous page.
     */
    record Query(String q, String scope, String path, String type, Long modifiedFrom, String sort, String cursor,
                 int limit) {
    }

    /** A run of text, matched or not; results carry text this way, never as HTML. */
    record Segment(String text, boolean match) {
    }

    /**
     * One result. {@code contents} says whether the file's text was searched: ok, unsupported, too_large, protected,
     * failed or pending.
     */
    record Hit(String path, List<Segment> name, String location, String type, boolean folder, long size,
               long modified, Long expiresAt, List<List<Segment>> snippets, String contents) {
    }

    record Result(long total, List<Hit> results, String nextCursor) {
    }

    record Suggestion(String path, String name, String location, boolean folder) {
    }

    /** This node's index, for the admin screen. */
    record Status(boolean available, long documents, long sizeBytes, long appliedSeq, long latestSeq,
                  double lagSeconds, Map<String, Long> extract, boolean rebuilding) {
    }

    boolean available();

    Result search(Principal p, Query q);

    List<Suggestion> suggest(Principal p, String q);

    Status status(Principal admin);

    void rebuild(Principal admin);

    /** Starts indexing in the background. */
    void start();

    @Override
    void close();
}
