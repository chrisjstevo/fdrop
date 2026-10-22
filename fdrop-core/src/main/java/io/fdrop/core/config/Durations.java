package io.fdrop.core.config;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses durations written in config as {@code 30s}, {@code 15m}, {@code 24h}, {@code 7d} or ISO-8601. */
public final class Durations {

    private static final Pattern SHORT = Pattern.compile("^\\s*(\\d+)\\s*(ms|s|m|h|d)\\s*$");

    private Durations() {
    }

    public static Duration parse(String text) {
        Matcher m = SHORT.matcher(text.toLowerCase(Locale.ROOT));
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> throw new IllegalStateException();
            };
        }
        return Duration.parse(text.trim().toUpperCase(Locale.ROOT));
    }
}
