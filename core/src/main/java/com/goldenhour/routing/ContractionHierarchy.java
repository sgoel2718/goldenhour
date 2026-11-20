package com.goldenhour.routing;

import com.goldenhour.ds.IndexedMinHeap;
import com.goldenhour.graph.RoadGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contraction Hierarchies (Geisberger et al., 2008) — the speed-up technique behind OSRM and GraphHopper.
 *
 * <p><b>Preprocessing.</b> Nodes are "contracted" one by one in order of importance (least important first:
 * dead ends and residential streets before motorway junctions). Contracting v removes it from the graph;
 * for every pair of neighbours u→v→x whose shortest path really goes through v, a <i>shortcut</i> u→x with
 * weight w(u,v)+w(v,x) is added. A bounded local Dijkstra ("witness search") checks whether some other
 * path u⇝x is already as short, in which case no shortcut is needed.
 *
 * <p><b>Query.</b> Every shortest path can be rewritten as "up the hierarchy, then down". So a bidirectional
 * Dijkstra where both sides only relax edges towards <i>more important</i> nodes finds the optimum, while
 * settling a few hundred nodes instead of thousands. Shortcuts are unpacked recursively to recover the
 * real road path.
 *
 * <p>Node order: lazy updates on the key {@code edgeDifference + deletedNeighbours + level}, kept in an
 * indexed heap. Correctness never depends on the order or on the witness-search limit — those only
 * affect the number of shortcuts and the query speed.
 */
public final class ContractionHierarchy implements Router {
    // ---- all edges (original + shortcuts) -------------------------------------------------------
    private int m;
    private int[] eFrom, eTo, eChildA, eChildB;
    private double[] eW;

    // ---- query graph: CSR of upward edges ------------------------------------------------------
    private final int n;
    private final int[] rank;
    private int[] upOutStart, upOutEdge, upInStart, upInEdge;
    private final int shortcuts;

    // ---- query buffers ----------------------------------------------------------------------------
    private final IndexedMinHeap hf, hb;
    private final double[] df, db;
    private final int[] pf, pb, touchedF, touchedB;
    private int tf, tb;

    public ContractionHierarchy(RoadGraph g) { this(g, 60); }

    public ContractionHierarchy(RoadGraph g, int witnessSettleLimit) {
        n = g.nodeCount();
        int m0 = g.edgeCount();
        int cap = m0 * 3 + 16;
        eFrom = new int[cap]; eTo = new int[cap]; eW = new double[cap]; eChildA = new int[cap]; eChildB = new int[cap];
        IntList[] out = new IntList[n], in = new IntList[n];
        for (int v = 0; v < n; v++) { out[v] = new IntList(); in[v] = new IntList(); }
        for (int u = 0; u < n; u++)
            for (int e = g.outBegin(u); e < g.outEnd(u); e++) addEdge(u, g.outTarget(e), g.outWeight(e), -1, -1, out, in);
        rank = new int[n];
        shortcuts = contractAll(out, in, witnessSettleLimit);
        buildUpwardGraph();
        hf = new IndexedMinHeap(n); hb = new IndexedMinHeap(n);
        df = new double[n]; db = new double[n]; pf = new int[n]; pb = new int[n];
        touchedF = new int[n]; touchedB = new int[n];
        Arrays.fill(df, Double.POSITIVE_INFINITY); Arrays.fill(db, Double.POSITIVE_INFINITY);
    }

    @Override public String name() { return "contraction-hierarchies"; }
    public int shortcutCount() { return shortcuts; }

    private int addEdge(int u, int x, double w, int a, int b, IntList[] out, IntList[] in) {
        if (m == eFrom.length) {
            int c = m * 2;
            eFrom = Arrays.copyOf(eFrom, c); eTo = Arrays.copyOf(eTo, c); eW = Arrays.copyOf(eW, c);
            eChildA = Arrays.copyOf(eChildA, c); eChildB = Arrays.copyOf(eChildB, c);
        }
        eFrom[m] = u; eTo[m] = x; eW[m] = w; eChildA[m] = a; eChildB[m] = b;
        out[u].add(m); in[x].add(m);
        return m++;
    }

    // ================================ preprocessing =============================================

    private int contractAll(IntList[] out, IntList[] in, int limit) {
        boolean[] done = new boolean[n];
        int[] deleted = new int[n], level = new int[n];
        Witness w = new Witness(n);
        IndexedMinHeap order = new IndexedMinHeap(n);
        for (int v = 0; v < n; v++) order.pushOrDecrease(v, importance(v, out, in, done, deleted, level, w, limit));
        int next = 0, added = 0;
        while (!order.isEmpty()) {
            int v = order.pop();
            double imp = importance(v, out, in, done, deleted, level, w, limit);
            if (!order.isEmpty() && imp > order.peekKey()) { order.pushOrDecrease(v, imp); continue; } // lazy update
            added += contract(v, out, in, done, w, limit, false);
            done[v] = true;
            rank[v] = next++;
            for (int i = 0; i < out[v].size; i++) { int x = eTo[out[v].get(i)]; if (!done[x]) { deleted[x]++; level[x] = Math.max(level[x], level[v] + 1); } }
            for (int i = 0; i < in[v].size; i++) { int u = eFrom[in[v].get(i)]; if (!done[u]) { deleted[u]++; level[u] = Math.max(level[u], level[v] + 1); } }
        }
        return added;
    }

    private double importance(int v, IntList[] out, IntList[] in, boolean[] done, int[] deleted, int[] level, Witness w, int limit) {
        int removed = 0;
        for (int i = 0; i < out[v].size; i++) if (!done[eTo[out[v].get(i)]]) removed++;
        for (int i = 0; i < in[v].size; i++) if (!done[eFrom[in[v].get(i)]]) removed++;
        int sc = contract(v, out, in, done, w, limit, true);
        return (sc - removed) + deleted[v] + level[v];
    }

    /** Adds (or, when {@code simulate}, only counts) the shortcuts needed to remove v. */
    private int contract(int v, IntList[] out, IntList[] in, boolean[] done, Witness w, int limit, boolean simulate) {
        compact(out[v], done, true); compact(in[v], done, false);
        int count = 0;
        for (int i = 0; i < in[v].size; i++) {
            int a = in[v].get(i), u = eFrom[a];
            if (done[u] || u == v) continue;
            double maxVia = 0;
            for (int j = 0; j < out[v].size; j++) {
                int b = out[v].get(j), x = eTo[b];
                if (!done[x] && x != u) maxVia = Math.max(maxVia, eW[a] + eW[b]);
            }
            if (maxVia == 0) continue;
            w.search(u, v, maxVia, limit, out, done);
            for (int j = 0; j < out[v].size; j++) {
                int b = out[v].get(j), x = eTo[b];
                if (done[x] || x == u) continue;
                double via = eW[a] + eW[b];
                if (w.dist(x) <= via) continue;            // a witness path avoids v: no shortcut
                count++;
                if (!simulate) { addEdge(u, x, via, a, b, out, in); w.improve(x, via); }
            }
        }
        return count;
    }

    /** Bounded Dijkstra on the not-yet-contracted graph, skipping the node being contracted. */
    private final class Witness {
        final IndexedMinHeap heap;
        final double[] d;
        final int[] touched;
        int t;

        Witness(int n) { heap = new IndexedMinHeap(n); d = new double[n]; touched = new int[n]; Arrays.fill(d, Double.POSITIVE_INFINITY); }

        void search(int s, int skip, double maxDist, int limit, IntList[] out, boolean[] done) {
            for (int i = 0; i < t; i++) d[touched[i]] = Double.POSITIVE_INFINITY;
            t = 0; heap.reset();
            set(s, 0);
            int settled = 0;
            while (!heap.isEmpty() && settled++ < limit) {
                int u = heap.pop();
                if (d[u] > maxDist) break;
                compact(out[u], done, true);
                for (int i = 0; i < out[u].size; i++) {
                    int e = out[u].get(i), x = eTo[e];
                    if (done[x] || x == skip) continue;
                    double nd = d[u] + eW[e];
                    if (nd < d[x]) set(x, nd);
                }
            }
        }

        double dist(int x) { return d[x]; }

        /** A shortcut just added is itself a witness for later neighbour pairs of the same u. */
        void improve(int x, double v) { if (v < d[x]) { if (d[x] == Double.POSITIVE_INFINITY) touched[t++] = x; d[x] = v; } }

        private void set(int x, double nd) {
            if (d[x] == Double.POSITIVE_INFINITY) touched[t++] = x;
            d[x] = nd; heap.pushOrDecrease(x, nd);
        }
    }

    /** Drops edges whose other endpoint is already contracted, keeping adjacency lists short. */
    private void compact(IntList list, boolean[] done, boolean byTarget) {
        int k = 0;
        for (int i = 0; i < list.size; i++) {
            int e = list.a[i];
            if (!done[byTarget ? eTo[e] : eFrom[e]]) list.a[k++] = e;
        }
        list.size = k;
    }

    private void buildUpwardGraph() {
        upOutStart = new int[n + 1]; upInStart = new int[n + 1];
        for (int e = 0; e < m; e++) {
            if (rank[eFrom[e]] < rank[eTo[e]]) upOutStart[eFrom[e] + 1]++;
            else upInStart[eTo[e] + 1]++;
        }
        for (int i = 0; i < n; i++) { upOutStart[i + 1] += upOutStart[i]; upInStart[i + 1] += upInStart[i]; }
        upOutEdge = new int[upOutStart[n]]; upInEdge = new int[upInStart[n]];
        int[] po = Arrays.copyOf(upOutStart, n), pi = Arrays.copyOf(upInStart, n);
        for (int e = 0; e < m; e++) {
            if (rank[eFrom[e]] < rank[eTo[e]]) upOutEdge[po[eFrom[e]]++] = e;
            else upInEdge[pi[eTo[e]]++] = e;
        }
    }

    // ================================ query =====================================================

    @Override
    public Route route(int s, int t) {
        for (int i = 0; i < tf; i++) df[touchedF[i]] = Double.POSITIVE_INFINITY;
        for (int i = 0; i < tb; i++) db[touchedB[i]] = Double.POSITIVE_INFINITY;
        tf = tb = 0; hf.reset(); hb.reset();
        if (s == t) return new Route(List.of(s), 0, 1);
        setF(s, 0, -1); setB(t, 0, -1);
        double best = Double.POSITIVE_INFINITY;
        int meet = -1, settled = 0;
        while (true) {
            boolean fOk = !hf.isEmpty() && hf.peekKey() < best, bOk = !hb.isEmpty() && hb.peekKey() < best;
            if (!fOk && !bOk) break;
            boolean forward = fOk && (!bOk || hf.peekKey() <= hb.peekKey());
            settled++;
            if (forward) {
                int u = hf.pop();
                if (db[u] + df[u] < best) { best = db[u] + df[u]; meet = u; }
                for (int i = upOutStart[u]; i < upOutStart[u + 1]; i++) {
                    int e = upOutEdge[i], x = eTo[e];
                    double d = df[u] + eW[e];
                    if (d < df[x]) setF(x, d, e);
                }
            } else {
                int u = hb.pop();
                if (db[u] + df[u] < best) { best = db[u] + df[u]; meet = u; }
                for (int i = upInStart[u]; i < upInStart[u + 1]; i++) {
                    int e = upInEdge[i], x = eFrom[e];
                    double d = db[u] + eW[e];
                    if (d < db[x]) setB(x, d, e);
                }
            }
        }
        if (meet < 0) return Route.unreachable(settled);
        List<Integer> edges = new ArrayList<>();
        for (int v = meet; pf[v] != -1; v = eFrom[pf[v]]) edges.add(pf[v]);
        java.util.Collections.reverse(edges);
        for (int v = meet; pb[v] != -1; v = eTo[pb[v]]) edges.add(pb[v]);
        List<Integer> path = new ArrayList<>();
        path.add(s);
        for (int e : edges) unpack(e, path);
        return new Route(path, best, settled);
    }

    private void unpack(int e, List<Integer> path) {
        if (eChildA[e] < 0) { path.add(eTo[e]); return; }
        unpack(eChildA[e], path);
        unpack(eChildB[e], path);
    }

    private void setF(int v, double d, int e) {
        if (df[v] == Double.POSITIVE_INFINITY) touchedF[tf++] = v;
        df[v] = d; pf[v] = e; hf.pushOrDecrease(v, d);
    }

    private void setB(int v, double d, int e) {
        if (db[v] == Double.POSITIVE_INFINITY) touchedB[tb++] = v;
        db[v] = d; pb[v] = e; hb.pushOrDecrease(v, d);
    }

    /** Growable primitive int list (avoids boxing millions of Integers during preprocessing). */
    private static final class IntList {
        int[] a = new int[4];
        int size;

        void add(int x) { if (size == a.length) a = Arrays.copyOf(a, size * 2); a[size++] = x; }

        int get(int i) { return a[i]; }
    }
}
