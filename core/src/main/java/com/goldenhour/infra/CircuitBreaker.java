package com.goldenhour.infra;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Circuit breaker around calls to the Python ML service.
 *
 * <p>Dispatch must never wait on ML: if triage/forecast calls keep failing or timing out, the breaker OPENS
 * and calls fail fast to a deterministic fallback (rule-based triage) for {@code coolDownMs}. Then it goes
 * HALF_OPEN and lets one trial call through; success closes it, failure re-opens it. This is graceful
 * degradation — the ambulance still goes out, with slightly less smart triage.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long coolDownMs;
    private final LongSupplier clock;
    private State state = State.CLOSED;
    private int failures;
    private long openedAt;
    private long fallbacks;

    public CircuitBreaker(int failureThreshold, long coolDownMs) { this(failureThreshold, coolDownMs, System::currentTimeMillis); }

    public CircuitBreaker(int failureThreshold, long coolDownMs, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.coolDownMs = coolDownMs;
        this.clock = clock;
    }

    public <T> T call(Supplier<T> action, Supplier<T> fallback) {
        synchronized (this) {
            if (state == State.OPEN) {
                if (clock.getAsLong() - openedAt >= coolDownMs) state = State.HALF_OPEN;
                else { fallbacks++; return fallback.get(); }
            }
        }
        try {
            T result = action.get();
            synchronized (this) { failures = 0; state = State.CLOSED; }
            return result;
        } catch (RuntimeException e) {
            synchronized (this) {
                failures++;
                if (state == State.HALF_OPEN || failures >= failureThreshold) { state = State.OPEN; openedAt = clock.getAsLong(); }
                fallbacks++;
            }
            return fallback.get();
        }
    }

    public synchronized State state() { return state; }
    public synchronized long fallbacks() { return fallbacks; }
}
