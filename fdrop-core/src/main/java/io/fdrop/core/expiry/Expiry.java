package io.fdrop.core.expiry;

import io.fdrop.core.error.FdropException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/**
 * A file expiry as people type it: one whole number and one unit ({@code 1sec}, {@code 6 days}, {@code 2weeks},
 * {@code 1year}), or {@code never}. See "Expiry syntax" in spec/file-expiry.md for the grammar. Seconds to weeks are fixed
 * lengths; months and years are calendar units added in UTC, clamped to the end of a shorter month.
 *
 * <p>{@code fdrop-web} and {@code fdrop-cli} have their own copies of this parser; all three must pass
 * {@code fdrop-core/src/test/resources/expiry-syntax-cases.json}.
 */
public final class Expiry {

    public static final String PROBLEM_INVALID = "invalid-expiry";
    public static final String PROBLEM_TOO_LONG = "expiry-too-long";
    public static final String PROBLEM_NOT_ALLOWED = "expiry-not-allowed";
    public static final String PROBLEM_CONFLICTING = "conflicting-expiry";

    public static final int MAX_COUNT = 999_999;
    public static final Expiry NEVER = new Expiry(0, null);

    public enum Unit {
        SECOND("second", 1), MINUTE("minute", 60), HOUR("hour", 3600), DAY("day", 86_400), WEEK("week", 604_800),
        MONTH("month", 0), YEAR("year", 0);

        private final String word;
        private final long seconds;

        Unit(String word, long seconds) {
            this.word = word;
            this.seconds = seconds;
        }
    }

    private static final String UNITS = "sec, min, hour, day, week, month, year";

    private static final Map<String, Unit> NAMES = Map.ofEntries(
            Map.entry("s", Unit.SECOND), Map.entry("sec", Unit.SECOND), Map.entry("secs", Unit.SECOND),
            Map.entry("second", Unit.SECOND), Map.entry("seconds", Unit.SECOND),
            Map.entry("min", Unit.MINUTE), Map.entry("mins", Unit.MINUTE), Map.entry("minute", Unit.MINUTE),
            Map.entry("minutes", Unit.MINUTE),
            Map.entry("h", Unit.HOUR), Map.entry("hr", Unit.HOUR), Map.entry("hrs", Unit.HOUR),
            Map.entry("hour", Unit.HOUR), Map.entry("hours", Unit.HOUR),
            Map.entry("d", Unit.DAY), Map.entry("day", Unit.DAY), Map.entry("days", Unit.DAY),
            Map.entry("w", Unit.WEEK), Map.entry("wk", Unit.WEEK), Map.entry("wks", Unit.WEEK),
            Map.entry("week", Unit.WEEK), Map.entry("weeks", Unit.WEEK),
            Map.entry("month", Unit.MONTH), Map.entry("months", Unit.MONTH),
            Map.entry("y", Unit.YEAR), Map.entry("yr", Unit.YEAR), Map.entry("yrs", Unit.YEAR),
            Map.entry("year", Unit.YEAR), Map.entry("years", Unit.YEAR));

    private final long count;
    private final Unit unit;

    private Expiry(long count, Unit unit) {
        this.count = count;
        this.unit = unit;
    }

    public static Expiry of(long count, Unit unit) {
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count out of range: " + count);
        }
        return new Expiry(count, unit);
    }

    /** Parses one expiry, throwing a {@code 400} with problem type {@code invalid-expiry} and a helpful detail. */
    public static Expiry parse(String text) {
        String s = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw invalid("Expiry is empty. Use a number and a unit, such as '6 days', or 'never'");
        }
        if (s.equals("never")) {
            return NEVER;
        }
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) {
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
        long count = Long.parseLong(digits);
        i = skipSpace(s, i);
        int unitStart = i;
        while (i < s.length() && !isSpace(s.charAt(i))) {
            i++;
        }
        String unitText = s.substring(unitStart, i);
        if (unitText.isEmpty()) {
            throw invalid("'" + digits + "' has no unit. Add one of " + UNITS);
        }
        if (skipSpace(s, i) < s.length()) {
            throw invalid("Only one number and one unit are allowed, such as '30 hours'");
        }
        Unit unit = NAMES.get(unitText);
        if (unit == null) {
            String letters = unitText.replaceAll("[0-9].*$", "");
            if (!letters.equals(unitText) && NAMES.containsKey(letters)) {
                throw invalid("Only one number and one unit are allowed, such as '30 hours'");
            }
            if (unitText.equals("m")) {
                throw invalid("'m' is ambiguous. Write '" + count + " min' or '" + count + " months'");
            }
            throw invalid("Unknown unit '" + unitText + "'. Use one of " + UNITS);
        }
        return new Expiry(count, unit);
    }

    private static String badNumber() {
        return "The number must be a whole number from 1 to " + MAX_COUNT
                + " without leading zeros. To keep a file forever use 'never'";
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static FdropException invalid(String detail) {
        return FdropException.badRequest(detail).withType(PROBLEM_INVALID);
    }

    /** Parses a nullable setting: null or blank means not set. */
    public static Expiry parseOptional(String text) {
        return text == null || text.isBlank() ? null : parse(text);
    }

    public boolean never() {
        return unit == null;
    }

    public long count() {
        return count;
    }

    public Unit unit() {
        return unit;
    }

    /** When a file whose clock starts at {@code start} expires; null for {@code never}. */
    public Instant from(Instant start) {
        if (unit == null) {
            return null;
        }
        return switch (unit) {
            case MONTH -> start.atZone(ZoneOffset.UTC).plusMonths(count).toInstant();
            case YEAR -> start.atZone(ZoneOffset.UTC).plusYears(count).toInstant();
            default -> start.plusSeconds(count * unit.seconds);
        };
    }

    /** True when this lasts longer than {@code other} for a file starting at {@code start}; never is longest. */
    public boolean longerThan(Expiry other, Instant start) {
        if (other.never()) {
            return false;
        }
        if (never()) {
            return true;
        }
        return from(start).isAfter(other.from(start));
    }

    /** {@code 1 day}, {@code 6 days}, {@code never}: how fdrop shows and stores an expiry. */
    public String canonical() {
        if (unit == null) {
            return "never";
        }
        return count + " " + unit.word + (count == 1 ? "" : "s");
    }

    @Override
    public String toString() {
        return canonical();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Expiry e && e.count == count && e.unit == unit;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(count) * 31 + (unit == null ? 0 : unit.hashCode());
    }
}
