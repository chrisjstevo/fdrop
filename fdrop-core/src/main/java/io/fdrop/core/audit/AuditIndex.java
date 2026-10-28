package io.fdrop.core.audit;

import java.util.List;
import java.util.Map;

/**
 * This node's Lucene index of the audit log (see "The audit index" in spec/audit-log.md), separate from the file search index.
 * Implemented by the fdrop-search module; without it, {@link NoAuditIndex} answers that audit search is unavailable
 * and the page still lists events from the DB. Callers check the admin role; the index does not.
 */
public interface AuditIndex extends AutoCloseable {

    /**
     * A search: free text and field syntax in {@code q}, and filters where each value list is ORed. {@code from} and
     * {@code to} are epoch millis; {@code beforeSeq} pages back from the previous page's last event.
     */
    record Query(String q, List<String> groups, List<String> events, String user, String ip, List<String> outcomes,
                 Long from, Long to, Long beforeSeq, int limit) {
    }

    /**
     * Matching events' {@code seq}s, newest first. {@code facets} holds counts per {@code group} and {@code outcome};
     * {@code indexedUpTo} is the time of the last event indexed (epoch millis), or null when the index is empty.
     */
    record Result(long total, List<Long> seqs, Map<String, Map<String, Long>> facets, Long indexedUpTo) {
    }

    record Status(boolean available, long documents, long sizeBytes, long appliedSeq, long latestSeq,
                  double lagSeconds, boolean rebuilding) {
    }

    boolean available();

    Result search(Query q);

    Status status();

    void rebuild();

    /** Starts following {@code audit_event} in the background. */
    void start();

    @Override
    void close();
}
