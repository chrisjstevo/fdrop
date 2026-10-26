package io.fdrop.search;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Text extraction, run in a separate short-lived JVM for each file: the document arrives on stdin, its text leaves on
 * stdout. Document parsers handle untrusted input, so a crash, a runaway or an exploit stays in this process, which
 * has a small heap and is killed at the time limit.
 *
 * <p>Exit codes: 0 text written, 2 failed, 3 password-protected.
 */
public final class ExtractMain {

    public static final int OK = 0;
    public static final int FAILED = 2;
    public static final int PROTECTED = 3;

    /** The outcome of an extraction: {@code state} OK, PROTECTED or FAILED. */
    public record Result(String state, String text) {
    }

    private ExtractMain() {
    }

    public static void main(String[] args) throws Exception {
        int maxChars = Integer.parseInt(args[0]);
        Result r = extract(System.in, maxChars);
        if (!r.state().equals("OK")) {
            System.exit(r.state().equals("PROTECTED") ? PROTECTED : FAILED);
        }
        OutputStream out = System.out;
        out.write(r.text().getBytes(StandardCharsets.UTF_8));
        out.flush();
        System.exit(OK);
    }

    /** Extracts up to {@code maxChars} characters of text. Also used in-process by tests. */
    public static Result extract(InputStream in, int maxChars) {
        BodyContentHandler handler = new BodyContentHandler(maxChars);
        Metadata meta = new Metadata();
        try (InputStream body = new BufferedInputStream(in)) {
            new AutoDetectParser().parse(body, handler, meta, new ParseContext());
        } catch (EncryptedDocumentException e) {
            return new Result("PROTECTED", null);
        } catch (Exception e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                return new Result("FAILED", null);
            }
        } catch (StackOverflowError | OutOfMemoryError e) {
            return new Result("FAILED", null);
        }
        // a document's title (an email's subject) is searchable too, ahead of its body
        String title = meta.get(TikaCoreProperties.TITLE);
        String text = handler.toString().strip();
        if (title != null && !title.isBlank() && !text.startsWith(title.strip())) {
            text = title.strip() + "\n" + text;
        }
        return new Result("OK", text.length() > maxChars ? text.substring(0, maxChars) : text);
    }
}
