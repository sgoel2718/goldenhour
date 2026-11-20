package com.goldenhour.ds;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Static 2-d tree over points (here: road-network nodes, projected to metres) for nearest-neighbour queries.
 *
 * <p>Used to "snap" a caller's GPS position to the closest road node. Built in O(n log n) by recursive
 * median split (nth_element-style quickselect, so O(n) per level), queried in O(log n) on average by
 * descending to the leaf and backtracking only into subtrees whose splitting plane is closer than the
 * current best. Stored implicitly in arrays (no node objects).
 */
public final class KdTree {
    private final double[] x, y;
    private final int[] idx;   // permutation of point ids arranged as an implicit balanced tree

    /** @param lat,lon coordinates in degrees; projected with an equirectangular approximation (fine at city scale). */
    public KdTree(double[] lat, double[] lon) {
        int n = lat.length;
        x = new double[n]; y = new double[n];
        double lat0 = Arrays.stream(lat).average().orElse(0);
        double kx = 111_320.0 * Math.cos(Math.toRadians(lat0)), ky = 110_540.0;
        for (int i = 0; i < n; i++) { x[i] = lon[i] * kx; y[i] = lat[i] * ky; }
        this.kx = kx; this.ky = ky;
        idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        build(0, n, 0);
    }

    private final double kx, ky;

    private void build(int lo, int hi, int depth) {
        if (hi - lo <= 1) return;
        int mid = (lo + hi) >>> 1;
        select(lo, hi - 1, mid, depth & 1);
        build(lo, mid, depth + 1);
        build(mid + 1, hi, depth + 1);
    }

    private double coord(int id, int axis) { return axis == 0 ? x[id] : y[id]; }

    /** Quickselect: places the k-th smallest (by axis) at position k. Expected O(hi-lo). */
    private void select(int lo, int hi, int k, int axis) {
        while (hi > lo) {
            double pivot = coord(idx[(lo + hi) >>> 1], axis);
            int i = lo, j = hi;
            while (i <= j) {
                while (coord(idx[i], axis) < pivot) i++;
                while (coord(idx[j], axis) > pivot) j--;
                if (i <= j) { int t = idx[i]; idx[i] = idx[j]; idx[j] = t; i++; j--; }
            }
            if (k <= j) hi = j; else if (k >= i) lo = i; else return;
        }
    }

    /** Id of the nearest point to (lat, lon). */
    public int nearest(double lat, double lon) { return kNearest(lat, lon, 1)[0]; }

    /** Ids of the k nearest points, closest first. */
    public int[] kNearest(double lat, double lon, int k) {
        double qx = lon * kx, qy = lat * ky;
        // max-heap of current best k (by squared distance)
        PriorityQueue<double[]> best = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
        search(0, idx.length, 0, qx, qy, k, best);
        int[] out = new int[best.size()];
        for (int i = out.length - 1; i >= 0; i--) out[i] = (int) best.poll()[1];
        return out;
    }

    private void search(int lo, int hi, int depth, double qx, double qy, int k, PriorityQueue<double[]> best) {
        if (hi <= lo) return;
        int mid = (lo + hi) >>> 1, id = idx[mid], axis = depth & 1;
        double dx = x[id] - qx, dy = y[id] - qy, d2 = dx * dx + dy * dy;
        if (best.size() < k) best.add(new double[]{d2, id});
        else if (d2 < best.peek()[0]) { best.poll(); best.add(new double[]{d2, id}); }
        double diff = (axis == 0 ? qx : qy) - coord(id, axis);
        boolean leftFirst = diff < 0;
        search(leftFirst ? lo : mid + 1, leftFirst ? mid : hi, depth + 1, qx, qy, k, best);
        if (best.size() < k || diff * diff < best.peek()[0])            // plane closer than worst kept point
            search(leftFirst ? mid + 1 : lo, leftFirst ? hi : mid, depth + 1, qx, qy, k, best);
    }
}
