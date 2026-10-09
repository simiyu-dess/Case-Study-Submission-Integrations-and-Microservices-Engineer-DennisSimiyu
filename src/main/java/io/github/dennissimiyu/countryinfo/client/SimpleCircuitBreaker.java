package io.github.dennissimiyu.countryinfo.client;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal thread-safe circuit breaker (CLOSED -> OPEN -> HALF_OPEN -> CLOSED).
 * Kept dependency-free to avoid coupling to a specific resilience library version.
 */
public class SimpleCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private volatile Instant openedAt = Instant.EPOCH;

    public SimpleCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /** @return true when a call is permitted. */
    public boolean allowRequest() {
        if (state.get() == State.OPEN) {
            if (clock.instant().isAfter(openedAt.plus(openDuration))) {
                return state.compareAndSet(State.OPEN, State.HALF_OPEN);
            }
            return false;
        }
        return true;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    public void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (state.get() == State.HALF_OPEN || failures >= failureThreshold) {
            openedAt = clock.instant();
            state.set(State.OPEN);
        }
    }

    public State getState() {
        return state.get();
    }
}
