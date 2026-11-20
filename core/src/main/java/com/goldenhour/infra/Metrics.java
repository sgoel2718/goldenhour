package com.goldenhour.infra;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Minimal Prometheus-compatible metrics: counters and fixed-bucket latency histograms (text exposition format). */
public final class Metrics {
    private static final double[] BUCKETS_MS = {0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50, 100, 250, 500, 1000};
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLongArray> histos = new ConcurrentHashMap<>();
    private final Map<String, double[]> sums = new ConcurrentHashMap<>();

    public void inc(String name) { counters.computeIfAbsent(name, k -> new LongAdder()).increment(); }

    public long count(String name) { LongAdder a = counters.get(name); return a == null ? 0 : a.sum(); }

    public void observeMs(String name, double ms) {
        AtomicLongArray h = histos.computeIfAbsent(name, k -> new AtomicLongArray(BUCKETS_MS.length + 1));
        int i = 0;
        while (i < BUCKETS_MS.length && ms > BUCKETS_MS[i]) i++;
        h.incrementAndGet(i);
        double[] s = sums.computeIfAbsent(name, k -> new double[2]);
        synchronized (s) { s[0] += ms; s[1]++; }
    }

    /** Approximate quantile from the histogram (upper bucket bound). */
    public double quantileMs(String name, double q) {
        AtomicLongArray h = histos.get(name);
        if (h == null) return Double.NaN;
        long total = 0;
        for (int i = 0; i < h.length(); i++) total += h.get(i);
        long target = (long) Math.ceil(q * total), run = 0;
        for (int i = 0; i < h.length(); i++) { run += h.get(i); if (run >= target) return i < BUCKETS_MS.length ? BUCKETS_MS[i] : Double.POSITIVE_INFINITY; }
        return Double.NaN;
    }

    public String prometheus() {
        StringBuilder sb = new StringBuilder();
        for (var e : new TreeMap<>(counters).entrySet())
            sb.append("# TYPE ").append(e.getKey()).append(" counter\n").append(e.getKey()).append(' ').append(e.getValue().sum()).append('\n');
        for (var e : new TreeMap<>(histos).entrySet()) {
            String n = e.getKey();
            sb.append("# TYPE ").append(n).append(" histogram\n");
            long cum = 0;
            for (int i = 0; i <= BUCKETS_MS.length; i++) {
                cum += e.getValue().get(i);
                sb.append(n).append("_bucket{le=\"").append(i < BUCKETS_MS.length ? BUCKETS_MS[i] : "+Inf").append("\"} ").append(cum).append('\n');
            }
            double[] s = sums.get(n);
            sb.append(n).append("_sum ").append(s[0]).append('\n').append(n).append("_count ").append((long) s[1]).append('\n');
        }
        return sb.toString();
    }
}
