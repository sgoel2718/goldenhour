package com.goldenhour.dispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Ambulance pre-positioning with the Maximum Expected Covering Location Problem (MEXCLP, Daskin 1983).
 *
 * <p>Each demand zone i has forecast weight w_i (expected calls next shift). An ambulance "covers" zone i
 * if its base can reach i within the target time T (e.g. 8 minutes). Ambulances are busy with probability
 * q, so a zone covered by k ambulances is served in time with probability 1 − q^k. We choose where to place
 * B ambulances (several may share a base) to maximise Σ_i w_i (1 − q^{k_i}).
 *
 * <p>The objective is monotone submodular (diminishing returns), so the greedy algorithm is within
 * (1 − 1/e) ≈ 63% of optimal — and in practice within a few percent. <b>Lazy greedy</b>: marginal gains
 * only ever decrease, so we keep candidates in a max-heap with possibly stale gains and only recompute the
 * top one; if it is still the best after recomputing, take it. This turns O(B·C·Z) into roughly
 * O(B·Z·log C) on real instances.
 */
public final class CoverageOptimizer {
    private CoverageOptimizer() {}

    /**
     * @param covers  covers[c] = indices of demand zones that candidate base c reaches within T
     * @param weight  forecast demand per zone
     * @param ambulances number of ambulances to place
     * @param busyProb system-wide busy fraction q (from utilisation)
     * @return base index chosen for each ambulance, in the order picked
     */
    public static List<Integer> place(int[][] covers, double[] weight, int ambulances, double busyProb) {
        int[] k = new int[weight.length];
        List<Integer> chosen = new ArrayList<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));   // {gain, cand, round}
        for (int c = 0; c < covers.length; c++) pq.add(new double[]{gain(covers[c], weight, k, busyProb), c, 0});
        for (int round = 0; round < ambulances && !pq.isEmpty(); round++) {
            while (true) {
                double[] top = pq.poll();
                int c = (int) top[1];
                if ((int) top[2] == round) {                // gain is fresh for this round: take it
                    chosen.add(c);
                    for (int z : covers[c]) k[z]++;
                    pq.add(new double[]{gain(covers[c], weight, k, busyProb), c, round + 1});
                    break;
                }
                pq.add(new double[]{gain(covers[c], weight, k, busyProb), c, round});   // refresh stale gain
            }
        }
        return chosen;
    }

    /** Marginal gain of one more ambulance at a base: Σ_{covered zones} w (1 − q) q^k. */
    static double gain(int[] covered, double[] w, int[] k, double q) {
        double g = 0;
        for (int z : covered) g += w[z] * (1 - q) * Math.pow(q, k[z]);
        return g;
    }

    /** Objective value Σ w_i (1 − q^{k_i}) for a placement (for reporting and tests). */
    public static double expectedCoverage(int[][] covers, double[] w, List<Integer> placement, double q) {
        int[] k = new int[w.length];
        for (int c : placement) for (int z : covers[c]) k[z]++;
        double s = 0;
        for (int i = 0; i < w.length; i++) s += w[i] * (1 - Math.pow(q, k[i]));
        return s;
    }
}
