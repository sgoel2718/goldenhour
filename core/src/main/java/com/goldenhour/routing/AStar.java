package com.goldenhour.routing;

import com.goldenhour.graph.RoadGraph;

/**
 * A* search: Dijkstra ordered by g(v) + h(v), where h(v) = straight-line distance to the target divided by
 * the fastest speed in the graph. Because no road is faster than {@code maxSpeed}, h never overestimates
 * (admissible) and satisfies the triangle inequality (consistent), so the first time the target is
 * popped its distance is optimal. Typical saving: 2–5× fewer settled nodes than Dijkstra on road networks.
 */
public final class AStar extends Dijkstra {
    public AStar(RoadGraph g) { super(g); }

    @Override public String name() { return "a-star"; }

    @Override
    protected double potential(int v, int target) { return g.lowerBoundSeconds(v, target); }
}
