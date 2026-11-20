package com.goldenhour;

import com.goldenhour.dispatch.CoverageOptimizer;
import com.goldenhour.dispatch.Hungarian;
import com.goldenhour.ds.ConsistentHashRing;
import com.goldenhour.ds.IndexedMinHeap;
import com.goldenhour.ds.KdTree;
import com.goldenhour.ds.LruCache;
import com.goldenhour.ds.SpatialGrid;
import com.goldenhour.ds.TokenBucket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import static com.goldenhour.Check.equal;
import static com.goldenhour.Check.isTrue;
import static com.goldenhour.Check.near;

/** Every data structure is checked against a brute-force oracle on randomised inputs. */
public final class DataStructuresTest {

    static double dist(double kx, double lat1, double lon1, double lat2, double lon2) {
        return Math.hypot((lon1 - lon2) * kx, (lat1 - lat2) * 110_540.0);
    }

    public static void testIndexedMinHeapMatchesPriorityQueue() {
        Random r = new Random(1);
        IndexedMinHeap h = new IndexedMinHeap(500);
        for (int round = 0; round < 20; round++) {
            h.reset();
            double[] best = new double[500];
            Arrays.fill(best, Double.POSITIVE_INFINITY);
            for (int i = 0; i < 2000; i++) {
                int id = r.nextInt(500);
                double k = r.nextDouble() * 100;
                if (k < best[id]) best[id] = k;
                h.pushOrDecrease(id, k);        // keeps the minimum key per id
            }
            PriorityQueue<double[]> ref = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
            for (int id = 0; id < 500; id++) if (best[id] < Double.POSITIVE_INFINITY) ref.add(new double[]{best[id], id});
            while (!ref.isEmpty()) {
                double expected = ref.poll()[0];
                near(expected, h.peekKey(), 1e-12, "heap order");
                h.pop();
            }
            isTrue(h.isEmpty(), "heap drained");
        }
    }

    public static void testKdTreeMatchesBruteForce() {
        Random r = new Random(2);
        int n = 5000;
        double[] lat = new double[n], lon = new double[n];
        for (int i = 0; i < n; i++) { lat[i] = 42.4 + r.nextDouble() * 0.25; lon[i] = 1.4 + r.nextDouble() * 0.35; }
        KdTree t = new KdTree(lat, lon);
        double kx = 111_320.0 * Math.cos(Math.toRadians(Arrays.stream(lat).average().orElseThrow()));
        for (int q = 0; q < 500; q++) {
            double a = 42.35 + r.nextDouble() * 0.35, b = 1.35 + r.nextDouble() * 0.45;
            int[] got = t.kNearest(a, b, 5);
            Integer[] all = new Integer[n];
            for (int i = 0; i < n; i++) all[i] = i;
            Arrays.sort(all, (x, y) -> Double.compare(dist(kx, a, b, lat[x], lon[x]), dist(kx, a, b, lat[y], lon[y])));
            for (int i = 0; i < 5; i++) near(dist(kx, a, b, lat[all[i]], lon[all[i]]), dist(kx, a, b, lat[got[i]], lon[got[i]]), 1e-6, "k-NN distance #" + i);
        }
    }

    public static void testSpatialGridKNearestWithMovesAndRemovals() {
        Random r = new Random(3);
        SpatialGrid g = new SpatialGrid(500, 42.5);
        Map<Integer, double[]> pos = new HashMap<>();
        double kx = 111_320.0 * Math.cos(Math.toRadians(42.5));
        for (int step = 0; step < 3000; step++) {
            int id = r.nextInt(200);
            if (r.nextDouble() < 0.15) { g.remove(id); pos.remove(id); }
            else { double[] p = {42.4 + r.nextDouble() * 0.3, 1.4 + r.nextDouble() * 0.4}; g.upsert(id, p[0], p[1]); pos.put(id, p); }
            if (step % 10 == 0) {
                double a = 42.4 + r.nextDouble() * 0.3, b = 1.4 + r.nextDouble() * 0.4;
                List<Integer> got = g.kNearest(a, b, 8);
                List<double[]> ref = new ArrayList<>();
                for (var e : pos.entrySet()) ref.add(new double[]{dist(kx, a, b, e.getValue()[0], e.getValue()[1]), e.getKey()});
                ref.sort((x, y) -> Double.compare(x[0], y[0]));
                equal(Math.min(8, pos.size()), got.size(), "result size");
                for (int i = 0; i < got.size(); i++) {
                    double[] p = pos.get(got.get(i));
                    near(ref.get(i)[0], dist(kx, a, b, p[0], p[1]), 1e-6, "grid k-NN #" + i);
                }
            }
        }
        equal(pos.size(), g.size(), "size tracks upserts/removals");
    }

    public static void testLruEvictsLeastRecentlyUsed() {
        LruCache<String, Integer> c = new LruCache<>(2);
        c.put("a", 1); c.put("b", 2);
        equal(1, c.get("a"), "hit a");          // a is now most recent
        c.put("c", 3);                          // evicts b
        equal(null, c.get("b"), "b evicted");
        equal(1, c.get("a"), "a kept");
        equal(3, c.get("c"), "c kept");
        near(0.75, c.hitRate(), 1e-9, "hit rate = 3 hits / 4 lookups");
    }

    public static void testTokenBucketRefillsAtConfiguredRate() {
        long[] now = {0};
        TokenBucket b = new TokenBucket(5, 2.0, () -> now[0]);      // burst 5, 2 req/s sustained
        int ok = 0;
        for (int i = 0; i < 10; i++) if (b.tryAcquire("client-1")) ok++;
        equal(5, ok, "burst capacity");
        isTrue(b.tryAcquire("client-2"), "buckets are per client");
        now[0] += 1_000_000_000L;                                      // +1 s => +2 tokens
        isTrue(b.tryAcquire("client-1") && b.tryAcquire("client-1"), "refilled two");
        isTrue(!b.tryAcquire("client-1"), "and no more");
        now[0] += 3_600_000_000_000L;                                  // an hour later: capped at capacity
        ok = 0;
        for (int i = 0; i < 10; i++) if (b.tryAcquire("client-1")) ok++;
        equal(5, ok, "refill capped at capacity");
    }

    public static void testConsistentHashingMovesAboutOneOverN() {
        ConsistentHashRing ring = new ConsistentHashRing(200);
        for (int i = 0; i < 10; i++) ring.addNode("dispatch-" + i);
        int keys = 20_000;
        String[] before = new String[keys];
        Map<String, Integer> load = new HashMap<>();
        for (int k = 0; k < keys; k++) { before[k] = ring.nodeFor("region-" + k); load.merge(before[k], 1, Integer::sum); }
        int max = load.values().stream().max(Integer::compare).orElseThrow();
        isTrue(max < keys / 10 * 1.35, "virtual nodes keep load balanced, max " + max);
        ring.addNode("dispatch-10");
        int moved = 0;
        for (int k = 0; k < keys; k++) {
            String now = ring.nodeFor("region-" + k);
            if (!now.equals(before[k])) { moved++; equal("dispatch-10", now, "keys only move TO the new node"); }
        }
        double frac = moved / (double) keys;
        isTrue(frac > 0.05 && frac < 0.14, "≈1/11 of keys move, got " + frac);
    }

    public static void testHungarianIsOptimal() {
        Random r = new Random(4);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + r.nextInt(6), m = n + r.nextInt(3);
            double[][] c = new double[n][m];
            for (double[] row : c) for (int j = 0; j < m; j++) row[j] = r.nextInt(100);
            int[] a = Hungarian.solve(c);
            boolean[] used = new boolean[m];
            double cost = 0;
            for (int i = 0; i < n; i++) { isTrue(!used[a[i]], "assignment is a matching"); used[a[i]] = true; cost += c[i][a[i]]; }
            near(brute(c, 0, new boolean[m]), cost, 1e-9, "optimal cost");
        }
    }

    static double brute(double[][] c, int i, boolean[] used) {
        if (i == c.length) return 0;
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < c[0].length; j++) if (!used[j]) { used[j] = true; best = Math.min(best, c[i][j] + brute(c, i + 1, used)); used[j] = false; }
        return best;
    }

    /** Lazy greedy on a monotone submodular objective is guaranteed ≥ (1 − 1/e) of optimal; in practice far closer. */
    public static void testCoverageGreedyNearOptimal() {
        Random r = new Random(5);
        double worst = 1;
        for (int trial = 0; trial < 60; trial++) {
            int cands = 6 + r.nextInt(4), zones = 12, p = 2 + r.nextInt(2);
            int[][] covers = new int[cands][];
            for (int c = 0; c < cands; c++) {
                List<Integer> zs = new ArrayList<>();
                for (int z = 0; z < zones; z++) if (r.nextDouble() < 0.3) zs.add(z);
                covers[c] = zs.stream().mapToInt(Integer::intValue).toArray();
            }
            double[] w = new double[zones];
            for (int z = 0; z < zones; z++) w[z] = r.nextDouble() * 10;
            double q = 0.3;
            double greedy = CoverageOptimizer.expectedCoverage(covers, w, CoverageOptimizer.place(covers, w, p, q), q);
            double opt = bestMultiset(covers, w, p, q, 0, new ArrayList<>());
            isTrue(greedy >= (1 - 1 / Math.E) * opt - 1e-9, "approximation guarantee");
            worst = Math.min(worst, opt == 0 ? 1 : greedy / opt);
        }
        isTrue(worst > 0.9, "greedy within 10% of optimal on all instances, worst " + worst);
    }

    static double bestMultiset(int[][] covers, double[] w, int p, double q, int from, List<Integer> cur) {
        if (cur.size() == p) return CoverageOptimizer.expectedCoverage(covers, w, cur, q);
        double best = 0;
        for (int c = from; c < covers.length; c++) {       // multisets: several units may share a base
            cur.add(c);
            best = Math.max(best, bestMultiset(covers, w, p, q, c, cur));
            cur.remove(cur.size() - 1);
        }
        return best;
    }
}
