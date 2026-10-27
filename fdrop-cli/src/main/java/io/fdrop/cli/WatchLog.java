package io.fdrop.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One line per event on stderr: {@code time event message}, or a JSON object with {@code --json}. */
final class WatchLog {

    private final PrintStream err;
    private final boolean json;

    WatchLog(PrintStream err, boolean json) {
        this.err = err;
        this.json = json;
    }

    /** {@code fields} are alternating names and values, also shown in the plain line's message as given. */
    void event(String event, String message, Object... fields) {
        String now = Instant.now().toString();
        if (json) {
            ObjectNode n = Api.JSON.createObjectNode();
            n.put("time", now);
            n.put("event", event);
            n.put("message", message);
            Map<String, Object> f = new LinkedHashMap<>();
            for (int i = 0; i + 1 < fields.length; i += 2) {
                f.put(String.valueOf(fields[i]), fields[i + 1]);
            }
            f.forEach((k, v) -> {
                if (v == null) {
                    n.putNull(k);
                } else if (v instanceof Number num) {
                    n.put(k, num.longValue());
                } else if (v instanceof Boolean b) {
                    n.put(k, b);
                } else {
                    n.put(k, v.toString());
                }
            });
            synchronized (err) {
                err.println(n);
            }
        } else {
            synchronized (err) {
                err.println(now + " " + event + " " + message);
            }
        }
    }
}
