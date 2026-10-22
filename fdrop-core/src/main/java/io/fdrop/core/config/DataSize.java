package io.fdrop.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A byte count written in config as {@code 64MiB}, {@code 8MB}, {@code 100GiB} or a plain number of bytes. */
public record DataSize(long bytes) {

    private static final Pattern FORMAT = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]*)\\s*$");

    public DataSize {
        if (bytes < 0) {
            throw new IllegalArgumentException("size must not be negative: " + bytes);
        }
    }

    public static DataSize ofBytes(long bytes) {
        return new DataSize(bytes);
    }

    public static DataSize ofMiB(long mib) {
        return new DataSize(mib * 1024L * 1024L);
    }

    public static DataSize ofGiB(long gib) {
        return new DataSize(gib * 1024L * 1024L * 1024L);
    }

    @JsonCreator
    public static DataSize parse(String text) {
        Matcher m = FORMAT.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a size: " + text);
        }
        double value = Double.parseDouble(m.group(1));
        long multiplier = switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "", "b" -> 1L;
            case "kb" -> 1000L;
            case "kib", "k" -> 1024L;
            case "mb" -> 1000L * 1000L;
            case "mib", "m" -> 1024L * 1024L;
            case "gb" -> 1000L * 1000L * 1000L;
            case "gib", "g" -> 1024L * 1024L * 1024L;
            case "tb" -> 1000L * 1000L * 1000L * 1000L;
            case "tib", "t" -> 1024L * 1024L * 1024L * 1024L;
            default -> throw new IllegalArgumentException("unknown size unit in: " + text);
        };
        return new DataSize(Math.round(value * multiplier));
    }

    @JsonValue
    @Override
    public String toString() {
        return bytes + "B";
    }
}
