package com.goldenhour.ds;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-client token-bucket rate limiter. Each client has a bucket of {@code capacity} tokens refilled at
 * {@code ratePerSecond}; a request spends one token or is rejected with HTTP 429. Allows short bursts
 * (a hospital system replaying a backlog) while capping the sustained rate. Refill is computed lazily
 * from elapsed time, so there is no background thread. The clock is injectable for deterministic tests.
 */
public final class TokenBucket {
    private final double capacity, ratePerNano;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, double[]> buckets = new ConcurrentHashMap<>(); // {tokens, lastNanos}

    public TokenBucket(double capacity, double ratePerSecond) { this(capacity, ratePerSecond, System::nanoTime); }

    public TokenBucket(double capacity, double ratePerSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.ratePerNano = ratePerSecond / 1e9;
        this.clock = nanoClock;
    }

    public boolean tryAcquire(String client) {
        long now = clock.getAsLong();
        double[] b = buckets.computeIfAbsent(client, c -> new double[]{capacity, now});
        synchronized (b) {
            b[0] = Math.min(capacity, b[0] + (now - (long) b[1]) * ratePerNano);
            b[1] = now;
            if (b[0] >= 1) { b[0] -= 1; return true; }
            return false;
        }
    }
}
