package io.fdrop.cli;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    /** A clock that only moves when the code under test sleeps. */
    static final class FakeClock implements Backoff.Clock {
        long now;
        final List<Duration> sleeps = new ArrayList<>();

        @Override
        public long nanos() {
            return now;
        }

        @Override
        public void sleep(Duration d) {
            sleeps.add(d);
            now += d.toNanos();
        }
    }

    @Test
    void doublesFromOneSecondToAMinuteWithJitterThenGivesUp() throws Exception {
        FakeClock clock = new FakeClock();
        Backoff b = new Backoff(clock, Duration.ofMinutes(5), new Random(42));
        while (b.pause()) {
            // keep failing
        }
        List<Duration> s = clock.sleeps;
        assertThat(s.get(0)).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(s.get(1)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(s.get(5)).isBetween(Duration.ofSeconds(16), Duration.ofSeconds(32));
        assertThat(s.subList(6, s.size() - 1)).allSatisfy(d -> assertThat(d)
                .isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60)));
        // never waits past the give-up time
        assertThat(Duration.ofNanos(clock.now)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void progressStartsOver() throws Exception {
        FakeClock clock = new FakeClock();
        Backoff b = new Backoff(clock, Duration.ofSeconds(10), new Random(1));
        b.pause();
        b.pause();
        b.pause();
        assertThat(b.step()).isEqualTo(Duration.ofSeconds(8));
        b.reset();
        assertThat(b.step()).isEqualTo(Duration.ofSeconds(1));
        // the deadline is measured from the first failure after a reset
        for (int i = 0; i < 3; i++) {
            assertThat(b.pause()).isTrue();
        }
    }

    @Test
    void zeroMeansNoRetries() throws Exception {
        assertThat(new Backoff(new FakeClock(), Duration.ZERO, new Random()).pause()).isFalse();
    }
}
