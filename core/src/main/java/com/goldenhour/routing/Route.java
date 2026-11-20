package com.goldenhour.routing;

import java.util.List;

/**
 * Result of a shortest-path query.
 *
 * @param nodes   node ids from source to target (empty if unreachable)
 * @param seconds total travel time
 * @param settled nodes permanently labelled by the search — the standard hardware-independent cost metric
 */
public record Route(List<Integer> nodes, double seconds, int settled) {
    public static Route unreachable(int settled) { return new Route(List.of(), Double.POSITIVE_INFINITY, settled); }
    public boolean found() { return !nodes.isEmpty(); }
}
