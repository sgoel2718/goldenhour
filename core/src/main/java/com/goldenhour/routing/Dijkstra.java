package com.goldenhour.routing;

import com.goldenhour.ds.IndexedMinHeap;
import com.goldenhour.graph.RoadGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Dijkstra's algorithm with an indexed heap — O((V + E) log V).
 *
 * <p>Also used with an A* potential (see {@link AStar}): A* is exactly Dijkstra on reduced costs
 * w'(u,v) = w(u,v) − h(u) + h(v), which stays non-negative when h is consistent.
 *
 * <p>Provides one-to-many searches bounded by a time budget: "every node reachable from this ambulance
 * base within 8 minutes" (forward) and "time from every node to this hospital" (backward on the reverse graph).
 */
public class Dijkstra implements Router {
    protected final RoadGraph g;
    protected final IndexedMinHeap heap;
    protected final double[] dist;
    protected final int[] parent;
    private final int[] touched;
    private int touchedCount;

    public Dijkstra(RoadGraph g) {
        this.g = g;
        int n = g.nodeCount();
        heap = new IndexedMinHeap(n);
        dist = new double[n];
        parent = new int[n];
        touched = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
    }

    @Override public String name() { return "dijkstra"; }

    /** Heuristic h(v) towards the target; 0 for plain Dijkstra. */
    protected double potential(int v, int target) { return 0; }

    @Override
    public Route route(int s, int t) {
        reset();
        relax(s, 0, -1, t);
        int settled = 0;
        while (!heap.isEmpty()) {
            int u = heap.pop();
            settled++;
            if (u == t) return new Route(path(t), dist[t], settled);
            for (int e = g.outBegin(u); e < g.outEnd(u); e++) relax(g.outTarget(e), dist[u] + g.outWeight(e), u, t);
        }
        return Route.unreachable(settled);
    }

    /** Travel time from s to every node within {@code budgetSeconds}; unreachable entries are +∞. */
    public double[] distancesFrom(int s, double budgetSeconds) { return oneToMany(s, budgetSeconds, false); }

    /** Travel time from every node TO t within the budget (search on the reverse graph). */
    public double[] distancesTo(int t, double budgetSeconds) { return oneToMany(t, budgetSeconds, true); }

    private double[] oneToMany(int root, double budget, boolean reverse) {
        reset();
        relax(root, 0, -1, -1);
        double[] out = new double[g.nodeCount()];
        Arrays.fill(out, Double.POSITIVE_INFINITY);
        while (!heap.isEmpty()) {
            int u = heap.pop();
            if (dist[u] > budget) break;
            out[u] = dist[u];
            if (reverse) for (int e = g.inBegin(u); e < g.inEnd(u); e++) relax(g.inSource(e), dist[u] + g.inWeight(e), u, -1);
            else for (int e = g.outBegin(u); e < g.outEnd(u); e++) relax(g.outTarget(e), dist[u] + g.outWeight(e), u, -1);
        }
        return out;
    }

    private void relax(int v, double d, int from, int target) {
        if (d < dist[v]) {
            if (dist[v] == Double.POSITIVE_INFINITY) touched[touchedCount++] = v;
            dist[v] = d;
            parent[v] = from;
            heap.pushOrDecrease(v, d + (target >= 0 ? potential(v, target) : 0));
        }
    }

    private List<Integer> path(int t) {
        List<Integer> p = new ArrayList<>();
        for (int v = t; v != -1; v = parent[v]) p.add(v);
        Collections.reverse(p);
        return p;
    }

    private void reset() {
        for (int i = 0; i < touchedCount; i++) dist[touched[i]] = Double.POSITIVE_INFINITY;
        touchedCount = 0;
        heap.reset();
    }
}
