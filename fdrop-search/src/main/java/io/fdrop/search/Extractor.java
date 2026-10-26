package io.fdrop.search;

import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Finds a file's type family and extracts its text, in a forked JVM ({@link ExtractMain}) with a small heap and a time
 * limit, or in this JVM for tests ({@code search.extract.isolation: inprocess}).
 */
final class Extractor {

    /** The result of one extraction: {@code state} OK, UNSUPPORTED, PROTECTED, TIMEOUT or FAILED. */
    record Outcome(String state, String text) {
    }

    private static final Set<String> SHEETS = Set.of("application/vnd.ms-excel", "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet");
    private static final Set<String> SLIDES = Set.of("application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation");
    private static final Set<String> DOCS = Set.of("application/msword", "application/rtf", "text/rtf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text");
    private static final Set<String> EMAIL = Set.of("message/rfc822", "application/vnd.ms-outlook");
    private static final Set<String> TEXT_APPS = Set.of("application/json", "application/xml",
            "application/javascript", "application/x-sh", "application/x-yaml", "application/sql");

    private final FdropConfig.SearchExtract cfg;
    private final Detector detector = TikaConfig.getDefaultConfig().getDetector();

    Extractor(FdropConfig.SearchExtract cfg) {
        this.cfg = cfg;
    }

    /** pdf, doc, sheet, slides, email, text or other, from the first bytes and the name. */
    String family(byte[] head, String fileName) {
        Metadata m = new Metadata();
        m.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        MediaType t;
        try {
            t = detector.detect(new ByteArrayInputStream(head), m);
        } catch (IOException e) {
            t = MediaType.OCTET_STREAM;
        }
        String base = t.getBaseType().toString();
        if (base.equals("application/pdf")) {
            return "pdf";
        }
        if (DOCS.contains(base)) {
            return "doc";
        }
        if (SHEETS.contains(base)) {
            return "sheet";
        }
        if (SLIDES.contains(base)) {
            return "slides";
        }
        if (EMAIL.contains(base)) {
            return "email";
        }
        if (t.getType().equals("text") || TEXT_APPS.contains(base) || base.endsWith("+xml")) {
            return "text";
        }
        return "other";
    }

    /** Extracts text from {@code in}; {@code family} "other" is never extracted. */
    Outcome extract(InputStream in) throws IOException {
        int maxChars = (int) Math.min(Integer.MAX_VALUE, cfg.maxTextPerFile.bytes());
        if ("inprocess".equals(cfg.isolation)) {
            ExtractMain.Result r = ExtractMain.extract(in, maxChars);
            return new Outcome(r.state(), r.text());
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx" + cfg.forkHeap, "-XX:+UseSerialGC",
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                ExtractMain.class.getName(), Integer.toString(maxChars));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        Thread feeder = Thread.ofVirtual().start(() -> {
            try (OutputStream to = proc.getOutputStream()) {
                in.transferTo(to);
            } catch (IOException ignored) {
                // the extractor stopped reading: it failed or reached its text limit
            }
        });
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream from = proc.getInputStream()) {
                byte[] b = new byte[64 * 1024];
                long cap = (long) maxChars * 4 + 1024;
                int n;
                while ((n = from.read(b)) >= 0) {
                    if (text.size() + n <= cap) {
                        text.write(b, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // the process was killed
            }
        });
        try {
            if (!proc.waitFor(Durations.parse(cfg.timeout).toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                feeder.interrupt();
                return new Outcome("TIMEOUT", null);
            }
            reader.join(5_000);
            return switch (proc.exitValue()) {
                case ExtractMain.OK -> new Outcome("OK", text.toString(StandardCharsets.UTF_8));
                case ExtractMain.PROTECTED -> new Outcome("PROTECTED", null);
                default -> new Outcome("FAILED", null);
            };
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Outcome("FAILED", null);
        }
    }
}
