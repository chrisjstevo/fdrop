package io.fdrop.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs a request up to {@code maxAttempts} times, waiting between tries with exponential backoff and jitter (or the
 * server's {@code Retry-After}). What is retried depends on whether the request is safe to repeat:
 * <ul>
 *   <li>always: failures to connect, {@code 429} and {@code 503}, where the server did not act on the request;</li>
 *   <li>only when safe to repeat: other network errors and timeouts, {@code 500}, {@code 502} and {@code 504},
 *       after which the request may or may not have taken effect.</li>
 * </ul>
 */
final class Retrier {

    /** Waiting, so tests can run without sleeping. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;

        Sleeper SYSTEM = Thread::sleep;
    }

    /** One try of a request: its response, or the I/O failure. */
    interface Attempt<T> {
        HttpResponse<T> run() throws IOException, InterruptedException;
    }

    /** A response that is worth another try, carried as the failure when the tries run out. */
    static final class RetryableResponse extends IOException {
        final int status;

        RetryableResponse(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final int maxAttempts;
    private final Duration initial;
    private final Duration max;
    private final Sleeper sleeper;

    Retrier(int maxAttempts, Duration initial, Duration max, Sleeper sleeper) {
        this.maxAttempts = maxAttempts;
        this.initial = initial;
        this.max = max;
        this.sleeper = sleeper;
    }

    int maxAttempts() {
        return maxAttempts;
    }

    /**
     * The first response that is not worth retrying (a success or a client error, for the caller to judge).
     *
     * @throws FdropRetriesExhaustedException after {@code maxAttempts} tries
     */
    <T> HttpResponse<T> run(String operation, boolean safeToRepeat, Attempt<T> attempt) {
        Duration step = initial;
        IOException last = null;
        for (int i = 1; i <= maxAttempts; i++) {
            Duration wait = step;
            try {
                HttpResponse<T> r = attempt.run();
                int s = r.statusCode();
                boolean retry = s == 429 || s == 503 || (safeToRepeat && (s == 500 || s == 502 || s == 504));
                if (!retry) {
                    return r;
                }
                close(r);
                last = new RetryableResponse(s, "HTTP " + s);
                wait = retryAfter(r).orElse(step);
            } catch (ConnectException | HttpConnectTimeoutException e) {
                last = e;
            } catch (javax.net.ssl.SSLException e) {
                if (!connectionLost(e)) {
                    throw new FdropClientException(operation + ": the TLS handshake failed (is the server's"
                            + " certificate trusted, and the client certificate accepted?): " + describe(e), e);
                }
                if (!safeToRepeat) {
                    throw new FdropClientException(operation + ": the connection failed and the request may have"
                            + " taken effect, so it is not repeated: " + describe(e), e);
                }
                last = e;
            } catch (IOException e) {
                if (!safeToRepeat) {
                    throw new FdropClientException(operation + ": the connection failed and the request may have"
                            + " taken effect, so it is not repeated: " + describe(e), e);
                }
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FdropClientException(operation + ": interrupted", e);
            }
            if (i == maxAttempts) {
                break;
            }
            pause(operation, jitter(wait));
            step = step.multipliedBy(2).compareTo(max) > 0 ? max : step.multipliedBy(2);
        }
        throw new FdropRetriesExhaustedException(operation, maxAttempts, last instanceof RetryableResponse ? last
                : new IOException(describe(last), last));
    }

    private void pause(String operation, Duration d) {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FdropClientException(operation + ": interrupted", e);
        }
    }

    /** Between half and all of the step, so many clients failing together do not retry together. */
    private Duration jitter(Duration d) {
        long ms = d.toMillis();
        return Duration.ofMillis(ms / 2 + (ms <= 1 ? 0 : ThreadLocalRandom.current().nextLong(ms - ms / 2)));
    }

    /** {@code Retry-After} in seconds, capped at the maximum backoff. */
    private Optional<Duration> retryAfter(HttpResponse<?> r) {
        return r.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                Duration d = Duration.ofSeconds(Long.parseLong(v.trim()));
                return Optional.of(d.compareTo(max) > 0 ? max : d);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private static void close(HttpResponse<?> r) {
        if (r.body() instanceof java.io.InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /** A TLS failure from a dropped connection, worth retrying, rather than a certificate either side refused. */
    static boolean connectionLost(javax.net.ssl.SSLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.io.EOFException || t instanceof java.net.SocketException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("Remote host terminated the handshake") || m.contains("Connection reset")
                    || m.contains("peer shut down incorrectly"))) {
                return true;
            }
        }
        return false;
    }

    static String describe(Throwable e) {
        if (e == null) {
            return "unknown failure";
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
