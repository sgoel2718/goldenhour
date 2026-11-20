package com.goldenhour.routing;

import com.goldenhour.ds.IndexedMinHeap;
import com.goldenhour.graph.RoadGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bidirectional Dijkstra: one search forward from s on the graph, one backward from t on the reverse graph,
 * alternating by the smaller frontier key. Each search explores a "ball" of radius ≈ d/2 instead of d, so
 * on a planar-ish road network the work drops roughly 2×.
 *
 * <p>Stopping rule (the classic subtle part): it is NOT correct to stop when the searches first meet.
 * We track μ = min over scanned edges (u,v) of df(u) + w + db(v) and stop when topF + topB ≥ μ.
 */
public final class BidirectionalDijkstra implements Router {
    private final RoadGraph g;
    private final IndexedMinHeap hf, hb;
    private final double[] df, db;
    private final int[] pf, pb, touchedF, touchedB;
    private int tf, tb;

    public BidirectionalDijkstra(RoadGraph g) {
        this.g = g;
        int n = g.nodeCount();
        hf = new IndexedMinHeap(n); hb = new IndexedMinHeap(n);
        df = new double[n]; db = new double[n];
        pf = new int[n]; pb = new int[n];
        touchedF = new int[n]; touchedB = new int[n];
        Arrays.fill(df, Double.POSITIVE_INFINITY);
        Arrays.fill(db, Double.POSITIVE_INFINITY);
    }

    @Override public String name() { return "bidirectional-dijkstra"; }

    @Override
    public Route route(int s, int t) {
        reset();
        if (s == t) return new Route(List.of(s), 0, 1);
        setF(s, 0, -1); setB(t, 0, -1);
        double mu = Double.POSITIVE_INFINITY;
        int meet = -1, settled = 0;
        while (!hf.isEmpty() && !hb.isEmpty()) {
            if (hf.peekKey() + hb.peekKey() >= mu) break;
            settled++;
            if (hf.peekKey() <= hb.peekKey()) {
                int u = hf.pop();
                for (int e = g.outBegin(u); e < g.outEnd(u); e++) {
                    int v = g.outTarget(e);
                    double d = df[u] + g.outWeight(e);
                    if (d < df[v]) setF(v, d, u);
                    if (db[v] < Double.POSITIVE_INFINITY && d + db[v] < mu) { mu = d + db[v]; meet = v; }
                }
            } else {
                int u = hb.pop();
                for (int e = g.inBegin(u); e < g.inEnd(u); e++) {
                    int v = g.inSource(e);
                    double d = db[u] + g.inWeight(e);
                    if (d < db[v]) setB(v, d, u);
                    if (df[v] < Double.POSITIVE_INFINITY && d + df[v] < mu) { mu = d + df[v]; meet = v; }
                }
            }
        }
        if (meet < 0) return Route.unreachable(settled);
        List<Integer> path = new ArrayList<>();
        for (int v = meet; v != -1; v = pf[v]) path.add(v);
        Collections.reverse(path);
        for (int v = pb[meet]; v != -1; v = pb[v]) path.add(v);
        return new Route(path, mu, settled);
    }

    private void setF(int v, double d, int p) {
        if (df[v] == Double.POSITIVE_INFINITY) touchedF[tf++] = v;
        df[v] = d; pf[v] = p; hf.pushOrDecrease(v, d);
    }

    private void setB(int v, double d, int p) {
        if (db[v] == Double.POSITIVE_INFINITY) touchedB[tb++] = v;
        db[v] = d; pb[v] = p; hb.pushOrDecrease(v, d);
    }

    private void reset() {
        for (int i = 0; i < tf; i++) df[touchedF[i]] = Double.POSITIVE_INFINITY;
        for (int i = 0; i < tb; i++) db[touchedB[i]] = Double.POSITIVE_INFINITY;
        tf = tb = 0;
        hf.reset(); hb.reset();
    }
}
