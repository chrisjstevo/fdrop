package io.fdrop.cli;

import java.time.Duration;
import java.util.Random;

/**
 * Exponential backoff with jitter: 1 s doubling to 60 s, each wait between half and all of the current step, until
 * a deadline. Time is injectable so tests run without sleeping.
 */
class Backoff {

    /** The clock and the sleep, so tests can fake both. */
    interface Clock {
        long nanos();

        void sleep(Duration d) throws InterruptedException;

        Clock SYSTEM = new Clock() {
            @Override
            public long nanos() {
                return System.nanoTime();
            }

            @Override
            public void sleep(Duration d) throws InterruptedException {
                Thread.sleep(d);
            }
        };
    }

    static final Duration FIRST = Duration.ofSeconds(1);
    static final Duration MAX = Duration.ofSeconds(60);

    private final Clock clock;
    private final Duration giveUpAfter;
    private final Random random;
    private Duration step = FIRST;
    private long failingSince = -1;

    Backoff(Clock clock, Duration giveUpAfter, Random random) {
        this.clock = clock;
        this.giveUpAfter = giveUpAfter;
        this.random = random;
    }

    /** Progress was made: the next failure starts from a 1 s wait and a fresh deadline. */
    void reset() {
        step = FIRST;
        failingSince = -1;
    }

    /**
     * Waits before the next attempt, or returns false when the failures have lasted longer than the give-up time.
     */
    boolean pause() throws InterruptedException {
        long now = clock.nanos();
        if (failingSince < 0) {
            failingSince = now;
        }
        long elapsed = now - failingSince;
        if (elapsed >= giveUpAfter.toNanos()) {
            return false;
        }
        long half = step.toMillis() / 2;
        Duration wait = Duration.ofMillis(half + (long) (random.nextDouble() * (step.toMillis() - half)));
        Duration left = Duration.ofNanos(giveUpAfter.toNanos() - elapsed);
        clock.sleep(wait.compareTo(left) < 0 ? wait : left);
        step = step.multipliedBy(2).compareTo(MAX) > 0 ? MAX : step.multipliedBy(2);
        return true;
    }

    Duration step() {
        return step;
    }
}
