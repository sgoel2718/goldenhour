package com.goldenhour;

import com.goldenhour.graph.RoadGraph;
import com.goldenhour.routing.AStar;
import com.goldenhour.routing.BidirectionalDijkstra;
import com.goldenhour.routing.ContractionHierarchy;
import com.goldenhour.routing.Dijkstra;
import com.goldenhour.routing.Route;
import com.goldenhour.routing.Router;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public final class RoutingTest {
    /** Real OpenStreetMap network when available (data/city), otherwise skipped by the real-map tests. */
    static RoadGraph city() throws Exception {
        Path p = Path.of(System.getProperty("gh.data", "../data/city"));
        return Files.exists(p.resolve("nodes.csv")) ? RoadGraph.load(p) : null;
    }

    /**
     * Random geometric graph: each node gets edges to its k nearest neighbours (plus some one-way and
     * random-weight noise). Road-like sparsity matters: CH on dense random graphs degenerates quadratically,
     * which is why it is a road-network technique.
     */
    static RoadGraph randomGraph(int n, int k, long seed) {
        Random r = new Random(seed);
        double[] lat = new double[n], lon = new double[n];
        for (int i = 0; i < n; i++) { lat[i] = 12.9 + r.nextDouble() * 0.1; lon[i] = 80.1 + r.nextDouble() * 0.1; }
        java.util.List<int[]> es = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int a = i;
            Integer[] idx = new Integer[n];
            for (int j = 0; j < n; j++) idx[j] = j;
            java.util.Arrays.sort(idx, java.util.Comparator.comparingDouble(j -> com.goldenhour.graph.Geo.haversineMeters(lat[a], lon[a], lat[j], lon[j])));
            for (int c = 1; c <= k; c++) { es.add(new int[]{a, idx[c]}); if (r.nextDouble() < 0.8) es.add(new int[]{idx[c], a}); }
        }
        int m = es.size();
        int[] f = new int[m], t = new int[m];
        double[] w = new double[m];
        for (int e = 0; e < m; e++) {
            f[e] = es.get(e)[0]; t[e] = es.get(e)[1];
            // weight >= straight-line time at 30 m/s so the A* heuristic stays admissible
            double d = com.goldenhour.graph.Geo.haversineMeters(lat[f[e]], lon[f[e]], lat[t[e]], lon[t[e]]);
            w[e] = d / 30.0 * (1 + r.nextDouble() * 2);
        }
        return RoadGraph.of(lat, lon, f, t, w);
    }

    static void agree(RoadGraph g, int queries, long seed) {
        Dijkstra ref = new Dijkstra(g);
        List<Router> others = List.of(new AStar(g), new BidirectionalDijkstra(g), new ContractionHierarchy(g));
        Random r = new Random(seed);
        for (int q = 0; q < queries; q++) {
            int s = r.nextInt(g.nodeCount()), t = r.nextInt(g.nodeCount());
            Route base = ref.route(s, t);
            for (Router o : others) {
                Route x = o.route(s, t);
                Check.isTrue(base.found() == x.found(), o.name() + " reachability differs for " + s + "->" + t);
                if (!base.found()) continue;
                Check.near(base.seconds(), x.seconds(), 1e-6, o.name() + " cost " + s + "->" + t);
                Check.equal(s, x.nodes().get(0), o.name() + " path start");
                Check.equal(t, x.nodes().get(x.nodes().size() - 1), o.name() + " path end");
                double sum = 0;
                for (int i = 0; i + 1 < x.nodes().size(); i++) sum += g.edgeWeight(x.nodes().get(i), x.nodes().get(i + 1));
                Check.near(x.seconds(), sum, 1e-6, o.name() + " path weights must add up (shortcut unpacking)");
            }
        }
    }

    public static void testAllRoutersAgreeOnRandomGraphs() {
        for (long seed = 1; seed <= 5; seed++) agree(randomGraph(400, 4, seed), 200, seed);
    }

    public static void testAllRoutersAgreeOnRealRoadNetwork() throws Exception {
        RoadGraph g = city();
        if (g == null) { System.out.println("    (skipped: run ml/data_prep/build_road_graph.py first)"); return; }
        agree(g, 300, 42);
    }

    /** Regression: OSM data contains self-loops; contracting them used to grow the list being iterated, forever. */
    public static void testSelfLoopsDoNotBreakContraction() {
        double[] lat = {0, 0, 0, 0.001}, lon = {0, 0.001, 0.002, 0.001};
        int[] from = {0, 1, 1, 1, 2, 3, 3, 1};
        int[] to   = {1, 0, 2, 1, 1, 3, 1, 3};       // 1→1 and 3→3 are self-loops
        double[] w = {10, 10, 10, 5, 10, 7, 4, 4};
        RoadGraph g = RoadGraph.of(lat, lon, from, to, w);
        agree(g, 16, 3);
    }

    public static void testOneToManyMatchesPointToPoint() {
        RoadGraph g = randomGraph(200, 4, 7);
        Dijkstra d = new Dijkstra(g);
        double[] from = d.distancesFrom(0, Double.POSITIVE_INFINITY), to = d.distancesTo(5, Double.POSITIVE_INFINITY);
        for (int v = 0; v < 200; v += 17) {
            Check.near(new Dijkstra(g).route(0, v).seconds(), from[v], 1e-9, "forward one-to-many");
            Check.near(new Dijkstra(g).route(v, 5).seconds(), to[v], 1e-9, "backward one-to-many");
        }
    }

    public static void testContractionHierarchySettlesFarFewerNodes() throws Exception {
        RoadGraph g = city();
        if (g == null) return;
        Dijkstra d = new Dijkstra(g);
        ContractionHierarchy ch = new ContractionHierarchy(g);
        Random r = new Random(3);
        long sd = 0, sc = 0;
        for (int q = 0; q < 200; q++) {
            int s = r.nextInt(g.nodeCount()), t = r.nextInt(g.nodeCount());
            sd += d.route(s, t).settled(); sc += ch.route(s, t).settled();
        }
        Check.isTrue(sc * 10 < sd, "CH should settle >10x fewer nodes, got " + sd + " vs " + sc);
    }
}
