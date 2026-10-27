package io.fdrop.core.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * The audit log: one JSON line per security-relevant event on the {@code fdrop.audit} logger, which the default
 * Logback config writes to its own file. Fields are added as name/value pairs.
 */
public final class Audit {

    private static final Logger log = LoggerFactory.getLogger("fdrop.audit");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Also receives every event, after its log line is written: the DB copy (see {@code AuditStore}). */
    public interface Sink {
        void accept(long ts, String event, ObjectNode fields, String line);
    }

    private static volatile Sink sink;

    public static void setSink(Sink s) {
        sink = s;
    }

    /** Removes {@code s} if it is still the sink, so a closed application stops receiving events. */
    public static void clearSink(Sink s) {
        if (sink == s) {
            sink = null;
        }
    }

    private Audit() {
    }

    /**
     * @param event  e.g. {@code upload}, {@code download}, {@code login}, {@code user.create}
     * @param fields alternating names and values
     */
    public static void log(String event, Object... fields) {
        ObjectNode n = JSON.createObjectNode();
        Instant now = Instant.now();
        n.put("ts", now.toString());
        n.put("event", event);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            Object v = fields[i + 1];
            String k = String.valueOf(fields[i]);
            if (v == null) {
                n.putNull(k);
            } else if (v instanceof Number num) {
                n.put(k, num.longValue());
            } else if (v instanceof Boolean b) {
                n.put(k, b);
            } else {
                n.put(k, v.toString());
            }
        }
        String line;
        try {
            line = JSON.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            log.warn("audit serialisation failed for {}", event);
            return;
        }
        log.info(line);
        Sink s = sink;
        if (s != null) {
            s.accept(now.toEpochMilli(), event, n, line);
        }
    }
}
