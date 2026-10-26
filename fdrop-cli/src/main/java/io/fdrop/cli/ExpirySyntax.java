package io.fdrop.cli;

import java.util.Locale;
import java.util.Map;

/**
 * Checks {@code --expires-in} before connecting, with the same messages as the server, and gives its canonical
 * form ({@code 6 days}). A copy of the server's parser ({@code io.fdrop.core.expiry.Expiry}), since the CLI does not
 * depend on {@code fdrop-core}; both pass {@code fdrop-core/src/test/resources/expiry-syntax-cases.json}. The
 * server checks again, including the folder's limits.
 */
final class ExpirySyntax {

    private static final int MAX_COUNT = 999_999;
    private static final String UNITS = "sec, min, hour, day, week, month, year";
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("s", "second"), Map.entry("sec", "second"), Map.entry("secs", "second"),
            Map.entry("second", "second"), Map.entry("seconds", "second"),
            Map.entry("min", "minute"), Map.entry("mins", "minute"), Map.entry("minute", "minute"),
            Map.entry("minutes", "minute"),
            Map.entry("h", "hour"), Map.entry("hr", "hour"), Map.entry("hrs", "hour"), Map.entry("hour", "hour"),
            Map.entry("hours", "hour"),
            Map.entry("d", "day"), Map.entry("day", "day"), Map.entry("days", "day"),
            Map.entry("w", "week"), Map.entry("wk", "week"), Map.entry("wks", "week"), Map.entry("week", "week"),
            Map.entry("weeks", "week"),
            Map.entry("month", "month"), Map.entry("months", "month"),
            Map.entry("y", "year"), Map.entry("yr", "year"), Map.entry("yrs", "year"), Map.entry("year", "year"),
            Map.entry("years", "year"));

    private ExpirySyntax() {
    }

    /** The canonical form, or a usage error (exit code 1) with the reason. */
    static String canonical(String text) {
        String s = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw invalid("Expiry is empty. Use a number and a unit, such as '6 days', or 'never'");
        }
        if (s.equals("never")) {
            return "never";
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == 0) {
            if (s.charAt(0) == '-' || s.charAt(0) == '+') {
                throw invalid(badNumber());
            }
            throw invalid("An expiry starts with a number, such as '6 days', or is 'never'");
        }
        String digits = s.substring(0, i);
        if (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == ',')) {
            throw invalid("Fractions are not supported. Use a smaller unit, such as '36 hours'");
        }
        if (digits.length() > 6) {
            throw invalid("The number must be a whole number from 1 to " + MAX_COUNT);
        }
        if (digits.charAt(0) == '0') {
            throw invalid(badNumber());
        }
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < s.length() && !isSpace(s.charAt(i))) {
            i++;
        }
        String unitText = s.substring(start, i);
        if (unitText.isEmpty()) {
            throw invalid("'" + digits + "' has no unit. Add one of " + UNITS);
        }
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        if (i < s.length()) {
            throw invalid("Only one number and one unit are allowed, such as '30 hours'");
        }
        String unit = NAMES.get(unitText);
        if (unit == null) {
            String letters = unitText.replaceAll("[0-9].*$", "");
            if (!letters.equals(unitText) && NAMES.containsKey(letters)) {
                throw invalid("Only one number and one unit are allowed, such as '30 hours'");
            }
            if (unitText.equals("m")) {
                throw invalid("'m' is ambiguous. Write '" + digits + " min' or '" + digits + " months'");
            }
            throw invalid("Unknown unit '" + unitText + "'. Use one of " + UNITS);
        }
        return digits + " " + unit + (digits.equals("1") ? "" : "s");
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    private static String badNumber() {
        return "The number must be a whole number from 1 to " + MAX_COUNT
                + " without leading zeros. To keep a file forever use 'never'";
    }

    private static CliException invalid(String message) {
        return new CliException(CliException.USAGE, message);
    }
}
