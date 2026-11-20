package com.goldenhour;

import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.dispatch.Model.Incident;
import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.dispatch.Model.Specialty;
import com.goldenhour.dispatch.Model.Unit;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.routing.AStar;
import com.goldenhour.routing.BidirectionalDijkstra;
import com.goldenhour.routing.ContractionHierarchy;
import com.goldenhour.routing.Dijkstra;
import com.goldenhour.routing.Route;
import com.goldenhour.routing.Router;
import com.goldenhour.sim.Scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Micro-benchmarks on the real road graph: point-to-point query latency (p50/p99) and search-space size for
 * each router on the same 2,000 random queries after JIT warm-up, CH preprocessing cost, and end-to-end dispatch
 * throughput. Writes ../reports/benchmarks.md (or reports/benchmarks.md when run from the repo root).
 */
public final class Benchmark {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "data/city");
        Path out = Path.of(args.length > 1 ? args[1] : "reports/benchmarks.md");
        RoadGraph g = RoadGraph.load(dir);
        List<String> md = new ArrayList<>();
        md.add("# Benchmarks\n");
        md.add(String.format("Road graph: %,d nodes, %,d directed edges. JDK %s, %d cores. 2,000 random origin–destination pairs, after 500 warm-up queries.%n",
            g.nodeCount(), g.edgeCount(), Runtime.version().feature(), Runtime.getRuntime().availableProcessors()));
        long t0 = System.nanoTime();
        ContractionHierarchy ch = new ContractionHierarchy(g);
        double build = (System.nanoTime() - t0) / 1e9;
        md.add(String.format("Contraction-hierarchy preprocessing: **%.2f s**, %,d shortcuts added (%.0f%% of original edges).%n", build, ch.shortcutCount(), 100.0 * ch.shortcutCount() / g.edgeCount()));
        md.add("| Router | p50 latency | p99 latency | mean nodes settled | speed-up vs Dijkstra (p50) |");
        md.add("|---|---:|---:|---:|---:|");
        Router[] routers = {new Dijkstra(g), new AStar(g), new BidirectionalDijkstra(g), ch};
        Random r = new Random(11);
        int[][] pairs = new int[2500][2];
        for (int[] p : pairs) { p[0] = r.nextInt(g.nodeCount()); p[1] = r.nextInt(g.nodeCount()); }
        double base = 0;
        for (Router router : routers) {
            for (int i = 0; i < 500; i++) router.route(pairs[i][0], pairs[i][1]);
            double[] lat = new double[2000];
            long settled = 0;
            for (int i = 0; i < 2000; i++) {
                long s = System.nanoTime();
                Route rt = router.route(pairs[500 + i][0], pairs[500 + i][1]);
                lat[i] = (System.nanoTime() - s) / 1e3;
                settled += rt.settled();
            }
            Arrays.sort(lat);
            double p50 = lat[1000], p99 = lat[1980];
            if (base == 0) base = p50;
            md.add(String.format("| %s | %.0f µs | %.0f µs | %,d | %.1f× |", router.name(), p50, p99, settled / 2000, base / p50));
            System.out.println(md.get(md.size() - 1));
        }
        // dispatch throughput: snap + k-nearest + 8 road ETAs + hospital choice, units released immediately
        DispatchEngine engine = new DispatchEngine(g, ch);
        Scenario sc = Scenario.load(dir, engine);
        sc.addHospitals(engine);
        for (int i = 0; i < 40; i++) engine.addAmbulance(i, i % 3 == 2 ? Unit.BLS : Unit.ALS, r.nextInt(g.nodeCount()));
        int n = 20_000;
        double[] lat = new double[n];
        for (int i = 0; i < n + 2000; i++) {
            int node = r.nextInt(g.nodeCount());
            Incident inc = new Incident("b" + i, g.lat(node), g.lon(node), engine.snap(g.lat(node), g.lon(node)), Severity.values()[i % 4], Specialty.GENERAL, "bench", i, "");
            long s = System.nanoTime();
            var as = engine.dispatch(inc);
            if (i >= 2000) lat[i - 2000] = (System.nanoTime() - s) / 1e3;
            as.ifPresent(a -> engine.release(a.ambulanceId(), inc.node()));
        }
        double total = Arrays.stream(lat).sum();
        Arrays.sort(lat);
        md.add(String.format("%nDispatch decision (k-nearest filter → 8 exact road ETAs via CH → ALS rule → hospital choice), 40 units, %,d incidents single-threaded: " +
            "**p50 %.0f µs, p99 %.0f µs, %,.0f decisions/s** on one core (uncached: every incident is at a new random location).%n", n, lat[n / 2], lat[(int) (n * 0.99)], n / (total / 1e6)));
        System.out.println(md.get(md.size() - 1));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, String.join("\n", md) + "\n");
    }
}
