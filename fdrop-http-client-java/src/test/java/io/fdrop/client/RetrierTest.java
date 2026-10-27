package io.fdrop.client;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What is retried, how often, and what is thrown when the tries run out; nothing sleeps. */
class RetrierTest {

    private final List<Duration> waits = new ArrayList<>();
    private final Retrier retrier = new Retrier(4, Duration.ofSeconds(1), Duration.ofSeconds(8), waits::add);

    static HttpResponse<byte[]> response(int status, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            public int statusCode() {
                return status;
            }

            public HttpRequest request() {
                return HttpRequest.newBuilder(URI.create("http://x/")).build();
            }

            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (a, b) -> true);
            }

            public byte[] body() {
                return new byte[0];
            }

            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            public URI uri() {
                return URI.create("http://x/");
            }

            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    static HttpResponse<byte[]> response(int status) {
        return response(status, Map.of());
    }

    @Test
    void retriesUntilItWorks() {
        AtomicInteger tries = new AtomicInteger();
        HttpResponse<byte[]> r = retrier.run("op", true, () -> tries.incrementAndGet() < 3 ? response(503)
                : response(200));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(tries).hasValue(3);
        assertThat(waits).hasSize(2);
        // jittered between half and all of 1 s, then of 2 s
        assertThat(waits.get(0)).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(waits.get(1)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void givesUpAfterMaxAttemptsWithTheLastFailure() {
        AtomicInteger tries = new AtomicInteger();
        assertThatThrownBy(() -> retrier.run("sending chunk 3", true, () -> {
            tries.incrementAndGet();
            throw new IOException("Connection reset");
        })).isInstanceOfSatisfying(FdropRetriesExhaustedException.class, e -> {
            assertThat(e.attempts()).isEqualTo(4);
            assertThat(e.getMessage()).contains("sending chunk 3", "gave up after 4 attempts", "Connection reset");
            assertThat(e.getCause()).hasMessageContaining("Connection reset");
        });
        assertThat(tries).hasValue(4);
        assertThat(waits).hasSize(3);
    }

    @Test
    void clientErrorsAreNotRetried() {
        AtomicInteger tries = new AtomicInteger();
        assertThat(retrier.run("op", true, () -> {
            tries.incrementAndGet();
            return response(403);
        }).statusCode()).isEqualTo(403);
        assertThat(tries).hasValue(1);
    }

    @Test
    void unsafeRequestsAreRetriedOnlyWhenTheyNeverArrived() {
        AtomicInteger tries = new AtomicInteger();
        assertThatThrownBy(() -> retrier.run("moving", false, () -> {
            tries.incrementAndGet();
            throw new IOException("timed out");
        })).isExactlyInstanceOf(FdropClientException.class).hasMessageContaining("may have taken effect");
        assertThat(tries).hasValue(1);

        tries.set(0);
        assertThat(retrier.run("moving", false, () -> {
            if (tries.incrementAndGet() == 1) {
                throw new ConnectException("Connection refused");
            }
            return response(200);
        }).statusCode()).isEqualTo(200);

        // a 502 might have come after the server acted
        tries.set(0);
        assertThat(retrier.run("moving", false, () -> {
            tries.incrementAndGet();
            return response(502);
        }).statusCode()).isEqualTo(502);
        assertThat(tries).hasValue(1);
    }

    @Test
    void honoursRetryAfterUpToTheMaximum() {
        AtomicInteger tries = new AtomicInteger();
        retrier.run("op", true, () -> tries.incrementAndGet() == 1
                ? response(429, Map.of("Retry-After", List.of("30"))) : response(200));
        // capped at 8 s, then jittered
        assertThat(waits.getFirst()).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void oneAttemptMeansNoRetry() {
        Retrier once = new Retrier(1, Duration.ofSeconds(1), Duration.ofSeconds(1), waits::add);
        assertThatThrownBy(() -> once.run("op", true, () -> response(503)))
                .isInstanceOfSatisfying(FdropRetriesExhaustedException.class,
                        e -> assertThat(e.getMessage()).contains("after 1 attempt:"));
        assertThat(waits).isEmpty();
    }
}
