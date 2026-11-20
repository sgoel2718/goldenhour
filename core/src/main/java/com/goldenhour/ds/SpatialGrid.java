package com.goldenhour.ds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uniform grid index for MOVING points (ambulance positions).
 *
 * <p>A k-d tree is ideal for static points but must be rebuilt when points move. Ambulances report their
 * position every few seconds, so we need O(1) updates: hash each point into a square cell (e.g. 500 m),
 * and answer "k nearest" by scanning rings of cells outward until the ring distance exceeds the k-th best
 * candidate. Same idea as Uber's H3 / geohash bucketing.
 */
public final class SpatialGrid {
    private final double cellMeters, kx, ky;
    private final Map<Long, Set<Integer>> cells = new HashMap<>();
    private final Map<Integer, double[]> where = new HashMap<>();   // id -> {x, y, cellKey}

    public SpatialGrid(double cellMeters, double refLat) {
        this.cellMeters = cellMeters;
        kx = 111_320.0 * Math.cos(Math.toRadians(refLat));
        ky = 110_540.0;
    }

    private long key(long cx, long cy) { return (cx << 32) ^ (cy & 0xffffffffL); }

    public synchronized void upsert(int id, double lat, double lon) {
        double px = lon * kx, py = lat * ky;
        long cx = (long) Math.floor(px / cellMeters), cy = (long) Math.floor(py / cellMeters), k = key(cx, cy);
        double[] old = where.get(id);
        if (old != null && (long) old[2] != k) cells.get((long) old[2]).remove(id);
        cells.computeIfAbsent(k, z -> new HashSet<>()).add(id);
        where.put(id, new double[]{px, py, k});
    }

    public synchronized void remove(int id) {
        double[] old = where.remove(id);
        if (old != null) cells.get((long) old[2]).remove(id);
    }

    public synchronized int size() { return where.size(); }

    /** Up to k ids nearest to (lat, lon) by straight-line distance, closest first. */
    public synchronized List<Integer> kNearest(double lat, double lon, int k) {
        double qx = lon * kx, qy = lat * ky;
        long cx = (long) Math.floor(qx / cellMeters), cy = (long) Math.floor(qy / cellMeters);
        List<double[]> found = new ArrayList<>();
        int maxRing = 400;
        for (int r = 0; r <= maxRing; r++) {
            // walk only the perimeter of the (2r+1)² square: 8r cells, not (2r+1)²
            for (long i = cx - r; i <= cx + r; i++) {
                long step = (i == cx - r || i == cx + r) ? 1 : Math.max(1, 2L * r);
                for (long j = cy - r; j <= cy + r; j += step) {
                    Set<Integer> s = cells.get(key(i, j));
                    if (s == null) continue;
                    for (int id : s) { double[] p = where.get(id); found.add(new double[]{Math.hypot(p[0] - qx, p[1] - qy), id}); }
                }
            }
            if (found.size() >= k) {
                found.sort((a, b) -> Double.compare(a[0], b[0]));
                // anything in ring r+1 or beyond is at least r * cell away
                if (found.get(k - 1)[0] <= r * cellMeters) break;
            }
            if (found.size() == where.size()) break;
        }
        found.sort((a, b) -> Double.compare(a[0], b[0]));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, found.size()); i++) out.add((int) found.get(i)[1]);
        return out;
    }
}
