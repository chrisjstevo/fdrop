package io.fdrop.core.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/**
 * What the audit log page shows for an event, derived from its name and fields: its group, outcome and one-line
 * summary. Derived again when the chain is verified, so the shown columns cannot be changed apart from the event.
 */
public final class AuditEvents {

    public static final List<String> GROUPS = List.of("Sign-in", "Files", "Folders and sharing", "Forwarding",
            "Expiry", "Accounts", "Encryption", "Search", "Audit", "System");

    /** Fields shown in a summary, in this order, when the event has them. */
    private static final List<String> MAIN = List.of("path", "from", "to", "folder", "file", "account", "member",
            "destination", "storageKey", "job", "method", "scope", "reason", "problem", "results", "bytes", "size");

    private AuditEvents() {
    }

    public static String group(String event) {
        String e = event.toLowerCase(Locale.ROOT);
        if (e.startsWith("login") || e.startsWith("logout") || e.contains("two-factor") || e.contains("twofactor")
                || e.equals("app.login") || e.startsWith("app.login.") || e.startsWith("app.auth")) {
            return "Sign-in";
        }
        if (e.startsWith("file.expir") || e.startsWith("folder.expiry")) {
            return "Expiry";
        }
        if (e.equals("upload") || e.equals("download") || e.equals("delete") || e.equals("move")
                || e.startsWith("file.")) {
            return "Files";
        }
        if (e.startsWith("folder.")) {
            return "Folders and sharing";
        }
        if (e.startsWith("forward") || e.startsWith("destination.")) {
            return "Forwarding";
        }
        if (e.startsWith("user.") || e.startsWith("app.") || e.startsWith("sshkey.")) {
            return "Accounts";
        }
        if (e.startsWith("storage.")) {
            return "Encryption";
        }
        if (e.startsWith("search")) {
            return "Search";
        }
        if (e.startsWith("audit.")) {
            return "Audit";
        }
        return "System";
    }

    /** A group as written in queries: {@code Folders and sharing} → {@code folders-and-sharing}. */
    public static String slug(String group) {
        return group.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    /** success, failure or refused, from the event's outcome or result field, or from its name. */
    public static String outcome(String event, JsonNode fields) {
        String v = fields.path("outcome").asText(fields.path("result").asText("")).toLowerCase(Locale.ROOT);
        String e = event.toLowerCase(Locale.ROOT);
        if (v.contains("refus") || v.contains("denied") || v.contains("not_allowed") || v.contains("rejected")
                || e.endsWith(".refused") || e.endsWith("refused")) {
            return "refused";
        }
        if (v.contains("fail") || v.contains("error") || e.endsWith(".failed") || e.endsWith(".broken")) {
            return "failure";
        }
        return "success";
    }

    public static String text(JsonNode fields, String name) {
        JsonNode v = fields.get(name);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** One line for the table: the event's name in words and its main fields. */
    public static String summary(String event, JsonNode fields) {
        String path = text(fields, "path");
        String what = switch (event) {
            case "upload" -> "uploaded " + nz(path);
            case "download" -> "downloaded " + nz(path);
            case "delete" -> "deleted " + nz(path);
            case "move" -> "moved " + nz(text(fields, "from")) + " → " + nz(text(fields, "to"));
            case "login" -> "signed in";
            case "logout" -> "signed out";
            default -> event.replace('.', ' ').replace('_', ' ');
        };
        StringBuilder b = new StringBuilder(what);
        boolean first = true;
        for (String k : MAIN) {
            if ((k.equals("path") && path != null && what.endsWith(path)) || ((k.equals("from") || k.equals("to"))
                    && event.equals("move"))) {
                continue;
            }
            String v = text(fields, k);
            if (v == null || v.isEmpty()) {
                continue;
            }
            if (k.equals("bytes") || k.equals("size")) {
                v = bytes(fields.path(k).asLong());
            }
            b.append(first ? ": " : ", ").append(k).append(' ').append(v);
            first = false;
        }
        return b.length() > 1000 ? b.substring(0, 997) + "…" : b.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String bytes(long n) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = n;
        int u = 0;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        return u == 0 ? n + " B" : String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }
}
