package com.goldenhour.dispatch;

import java.util.Arrays;

/**
 * Hungarian algorithm (Kuhn–Munkres with potentials), O(n² m) for an n×m cost matrix with n ≤ m.
 *
 * <p>Why not just "closest ambulance first"? When several calls arrive together (a bus crash, monsoon
 * flooding), greedy assignment can send the only ambulance near incident B to incident A, leaving B with a
 * 20-minute wait. The Hungarian algorithm minimises the TOTAL (severity-weighted) response time across all
 * pending incidents — the optimal bipartite matching.
 */
public final class Hungarian {
    private Hungarian() {}

    /** @return assignment[row] = column for each row (rows = incidents, columns = ambulances, rows ≤ cols). */
    public static int[] solve(double[][] cost) {
        int n = cost.length, m = cost[0].length;
        if (n > m) throw new IllegalArgumentException("need rows <= cols; add dummy columns");
        double[] u = new double[n + 1], v = new double[m + 1];
        int[] p = new int[m + 1], way = new int[m + 1];      // p[j] = row matched to column j (1-based)
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            boolean[] used = new boolean[m + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            do {                                             // grow an alternating tree until a free column is found
                used[j0] = true;
                int i0 = p[j0], j1 = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= m; j++) {
                    if (used[j]) continue;
                    double cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0; }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j; }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta; } else minv[j] -= delta;
                }
                j0 = j1;
            } while (p[j0] != 0);
            do { int j1 = way[j0]; p[j0] = p[j1]; j0 = j1; } while (j0 != 0);   // augment along the path
        }
        int[] assign = new int[n];
        for (int j = 1; j <= m; j++) if (p[j] != 0) assign[p[j] - 1] = j - 1;
        return assign;
    }
}
