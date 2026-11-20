package com.goldenhour.sim;

import com.goldenhour.dispatch.CoverageOptimizer;
import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.dispatch.Hungarian;
import com.goldenhour.dispatch.Model.Assignment;
import com.goldenhour.dispatch.Model.Incident;
import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.dispatch.Model.Unit;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.routing.Dijkstra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Discrete-event simulation of an EMS system — the way dispatch policies are evaluated before they touch
 * real patients. Time jumps from event to event (a min-heap ordered by time), so a simulated month runs in
 * seconds.
 *
 * <p>Lifecycle per call: arrival → (queue if no unit) → dispatch + 60 s "chute" time → drive (road network)
 * → on scene 12–20 min → transport to the best capable hospital (or treated on scene) → 15 min handover →
 * unit available at the hospital → drives back to its (possibly re-optimised) base.
 *
 * <p><b>Fairness of the comparison:</b> the base-placement optimiser never sees the true demand rates used to
 * generate calls. It only sees counts from {@code historyWeeks} of separately simulated history — a noisy,
 * realistic forecast. All policies face the identical call stream (same seed).
 */
public final class Simulator {
    public enum Placement { HOSPITAL_BASED, GEOGRAPHIC, FORECAST_STATIC, FORECAST_PER_SHIFT, DYNAMIC_MOVE_UP }

    public record Config(int ambulances, DispatchEngine.Policy policy, Placement placement, int weeks, int historyWeeks,
                         double volumeScale, long seed) {}

    public record Report(Config config, int calls, Map<Severity, double[]> responseMinutes, Map<Severity, Double> withinTarget,
                         double meanQueueMinutes, double utilisation, double wallSeconds) {
        public String line() {
            double[] p1 = responseMinutes.get(Severity.P1_CRITICAL), all = responseMinutes.get(null);
            return String.format("%-22s %-18s calls %5d | P1 median %4.1f p90 %4.1f min, ≤8 min %5.1f%% | all median %4.1f p90 %4.1f | queue %.2f min | util %4.1f%%",
                config.policy(), config.placement(), calls, p1[0], p1[1], 100 * withinTarget.get(Severity.P1_CRITICAL), all[0], all[1], meanQueueMinutes, 100 * utilisation);
        }
    }

    private static final double CHUTE = 60, HANDOVER = 15 * 60, TARGET_TRAVEL = 8 * 60 - CHUTE;
    private static final int SHIFT_HOURS = 8;
    private static final double SHRINK = 1.0;

    private final DispatchEngine engine;
    private final Scenario sc;
    private final int[][] covers;            // candidate base -> zones reachable within TARGET_TRAVEL
    private final int[] candidates;
    private double[] staticWeights;
    private int[] generation;
    private int moves;

    public Simulator(DispatchEngine engine, Scenario sc) {
        this.engine = engine;
        this.sc = sc;
        RoadGraph g = engine.graph();
        List<Integer> cand = new ArrayList<>();
        for (Scenario.Zone z : sc.zones) cand.add(z.node());
        for (var h : engine.hospitals()) cand.add(h.node);
        candidates = cand.stream().mapToInt(Integer::intValue).distinct().toArray();
        covers = new int[candidates.length][];
        Dijkstra d = new Dijkstra(g);
        for (int c = 0; c < candidates.length; c++) {
            double[] t = d.distancesFrom(candidates[c], TARGET_TRAVEL);
            List<Integer> zs = new ArrayList<>();
            for (int z = 0; z < sc.zones.size(); z++) if (t[sc.zones.get(z).node()] <= TARGET_TRAVEL) zs.add(z);
            covers[c] = zs.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    // ------------------------------------------------------------------ demand
    private record Call(double t, int zone, int caseType) {}

    private List<Call> generate(int weeks, double scale, long seed) {
        Random r = new Random(seed);
        List<Call> calls = new ArrayList<>();
        for (int h = 0; h < weeks * 168; h++)
            for (int z = 0; z < sc.zones.size(); z++) {
                int n = poisson(sc.zones.get(z).ratePerHour()[h % 168] * scale, r);
                for (int i = 0; i < n; i++) calls.add(new Call(3600.0 * (h + r.nextDouble()), z, sampleCase(r)));
            }
        calls.sort((a, b) -> Double.compare(a.t, b.t));
        return calls;
    }

    private int sampleCase(Random r) {
        double u = r.nextDouble(), c = 0;
        for (int i = 0; i < sc.caseMix.size(); i++) { c += sc.caseMix.get(i).share(); if (u <= c) return i; }
        return sc.caseMix.size() - 1;
    }

    private static int poisson(double lambda, Random r) {
        if (lambda <= 0) return 0;
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do { k++; p *= r.nextDouble(); } while (p > l);
        return k - 1;
    }

    /**
     * Placement per shift (3 shifts/day × 7 days) from zone weights.
     *
     * <p>Per-shift counts from 4 weeks of history are sparse (≈4 observations per zone per shift), so raw counts
     * overfit noise. We shrink them toward the stable spatial pattern (empirical Bayes):
     * {@code w_s[z] = c_s[z] + k · share[z] · n_s}, and give each shift its own busy probability, because
     * at peak hours more units are busy and the optimiser should buy redundant coverage of hot zones.
     */
    private int[][] plan(Placement placement, int ambulances, double meanBusy, double scale, int historyWeeks, long seed) {
        int shifts = 168 / SHIFT_HOURS, zones = sc.zones.size();
        int[][] bases = new int[shifts][];
        double[][] w = new double[shifts][zones];
        double[] busy = new double[shifts];
        Arrays.fill(busy, meanBusy);
        if (placement == Placement.HOSPITAL_BASED) {
            // Status quo in many systems: crews wait at hospitals. A sensible planner staffs the hospitals that can
            // reach the most populated zones within 8 minutes first, then round-robins.
            var hs = engine.hospitals();
            Integer[] order = new Integer[hs.size()];
            int[] reach = new int[hs.size()];
            for (int h = 0; h < hs.size(); h++) {
                order[h] = h;
                for (int c = 0; c < candidates.length; c++) if (candidates[c] == hs.get(h).node) reach[h] = covers[c].length;
            }
            Arrays.sort(order, (x, y) -> reach[y] - reach[x]);
            int[] b = new int[ambulances];
            for (int i = 0; i < ambulances; i++) b[i] = hs.get(order[i % hs.size()]).node;
            Arrays.fill(bases, b);
            return bases;
        }
        if (placement == Placement.GEOGRAPHIC) {
            for (double[] row : w) Arrays.fill(row, 1.0);
        } else {
            List<Call> hist = generate(historyWeeks, scale, seed ^ 0x9E3779B97F4A7C15L);    // observed history, NOT the true rates
            double[] total = new double[zones], perShift = new double[shifts];
            for (Call c : hist) {
                int s = (int) (c.t / 3600) % 168 / SHIFT_HOURS;
                w[s][c.zone]++; total[c.zone]++; perShift[s]++;
            }
            double all = Arrays.stream(total).sum();
            for (int s = 0; s < shifts; s++) {
                if (placement != Placement.FORECAST_PER_SHIFT) { w[s] = total; continue; }
                for (int z = 0; z < zones; z++) w[s][z] += SHRINK * total[z] / all * perShift[s];
                busy[s] = Math.min(0.9, meanBusy * perShift[s] / (all / shifts));
            }
        }
        staticWeights = w[0];
        for (int s = 0; s < shifts; s++) {
            if (s > 0 && w[s] == w[s - 1] && busy[s] == busy[s - 1]) { bases[s] = bases[s - 1]; continue; }
            List<Integer> pick = CoverageOptimizer.place(covers, w[s], ambulances, busy[s]);
            bases[s] = pick.stream().mapToInt(c -> candidates[c]).toArray();
        }
        return bases;
    }

    /** Base placement for a live deployment (the same planner the experiments use). */
    public int[] basesFor(Placement placement, int ambulances, double volumeScale, long seed) {
        double busy = Math.min(0.9, sc.totalRatePerWeek() * volumeScale / 168.0 / ambulances);
        return plan(placement, ambulances, busy, volumeScale, 4, seed)[0];
    }

    // ------------------------------------------------------------------ run
    private static final int ARRIVE = 0, ON_SCENE = 1, LEAVE_SCENE = 2, AT_HOSPITAL = 3, FREE = 4, BACK_AT_BASE = 5, SHIFT = 6;

    private record Ev(double t, int type, int a, int b, Object payload) {}

    public Report run(Config cfg) {
        long wall = System.nanoTime();
        engine.setPolicy(cfg.policy());
        for (var a : engine.ambulances()) throw new IllegalStateException("use a fresh engine per run (found ambulance " + a.id + ")");
        double callsPerHour = sc.totalRatePerWeek() * cfg.volumeScale() / 168.0;
        double busy = Math.min(0.9, callsPerHour * 1.0 / cfg.ambulances());     // ~1 h per job
        int[][] bases = plan(cfg.placement(), cfg.ambulances(), busy, cfg.volumeScale(), cfg.historyWeeks(), cfg.seed());
        for (int i = 0; i < cfg.ambulances(); i++) engine.addAmbulance(i, i % 3 == 2 ? Unit.BLS : Unit.ALS, bases[0][i]);
        generation = new int[cfg.ambulances()];
        moves = 0;
        boolean dynamic = cfg.placement() == Placement.DYNAMIC_MOVE_UP;

        List<Call> calls = generate(cfg.weeks(), cfg.volumeScale(), cfg.seed());
        PriorityQueue<Ev> q = new PriorityQueue<>((x, y) -> Double.compare(x.t, y.t));
        for (int i = 0; i < calls.size(); i++) q.add(new Ev(calls.get(i).t, ARRIVE, i, 0, null));
        for (int h = SHIFT_HOURS; h < cfg.weeks() * 168; h += SHIFT_HOURS) q.add(new Ev(h * 3600.0, SHIFT, h, 0, null));

        Map<String, Integer> callOf = new HashMap<>();
        Map<Integer, Incident> incidentOf = new HashMap<>();
        Map<Severity, List<Double>> resp = new EnumMap<>(Severity.class);
        List<Double> allResp = new ArrayList<>(), queueWait = new ArrayList<>();
        double busySeconds = 0;
        double[] busySince = new double[cfg.ambulances()];
        Random rnd = new Random(cfg.seed() + 1);

        while (!q.isEmpty()) {
            Ev e = q.poll();
            switch (e.type) {
                case ARRIVE -> {
                    Call c = calls.get(e.a);
                    Scenario.CaseType ct = sc.caseMix.get(c.caseType);
                    int node = sc.zones.get(c.zone).node();
                    var g = engine.graph();
                    Incident inc = new Incident("c" + e.a, g.lat(node), g.lon(node), node, ct.severity(), ct.need(), ct.category(), (long) (c.t * 1000), ct.category());
                    callOf.put(inc.id(), e.a);
                    engine.dispatch(inc).ifPresent(as -> {
                        generation[as.ambulanceId()]++;                 // cancels any pending move of this unit
                        start(as, e.t, q, incidentOf, inc, busySince);
                        if (dynamic) moveUp(e.t, q, busy);
                    });
                    incidentOf.putIfAbsent(-1 - e.a, inc);   // remember queued incidents too
                }
                case ON_SCENE -> {
                    Incident inc = (Incident) e.payload;
                    double r = (e.t - inc.createdAtMs() / 1000.0) / 60.0;
                    resp.computeIfAbsent(inc.severity(), k -> new ArrayList<>()).add(r);
                    allResp.add(r);
                    double scene = 12 * 60 + rnd.nextDouble() * 8 * 60;
                    q.add(new Ev(e.t + scene, LEAVE_SCENE, e.a, e.b, inc));
                }
                case LEAVE_SCENE -> {
                    Incident inc = (Incident) e.payload;
                    double pTransport = switch (inc.severity()) { case P1_CRITICAL, P2_EMERGENT -> 1.0; case P3_URGENT -> 0.7; case P4_ROUTINE -> 0.3; };
                    if (rnd.nextDouble() < pTransport && e.b >= 0) {
                        double t = engine.timeToHospital(e.b, inc.node());
                        q.add(new Ev(e.t + t, AT_HOSPITAL, e.a, e.b, inc));
                    } else q.add(new Ev(e.t, FREE, e.a, inc.node(), null));
                }
                case AT_HOSPITAL -> q.add(new Ev(e.t + HANDOVER, FREE, e.a, engine.hospitals().get(e.b).node, null));
                case FREE -> {
                    busySeconds += e.t - busySince[e.a];
                    Optional<Assignment> next = engine.release(e.a, e.b);
                    if (next.isPresent()) {
                        Assignment as = next.get();
                        generation[as.ambulanceId()]++;
                        Incident inc = findQueued(incidentOf, callOf, as.incidentId());
                        queueWait.add(e.t - inc.createdAtMs() / 1000.0);
                        start(as, e.t, q, incidentOf, inc, busySince);
                    } else {
                        var amb = engine.ambulances().get(e.a);
                        double back = engine.travelSeconds(e.b, amb.baseNode);
                        q.add(new Ev(e.t + back, BACK_AT_BASE, e.a, amb.baseNode, ++generation[e.a]));
                        if (dynamic) moveUp(e.t, q, busy);
                    }
                }
                case BACK_AT_BASE -> { if ((Integer) e.payload == generation[e.a]) engine.reposition(e.a, e.b); }
                case SHIFT -> {
                    if (dynamic) break;
                    int shift = (e.a % 168) / SHIFT_HOURS;
                    for (int i = 0; i < cfg.ambulances(); i++) {
                        engine.moveBase(i, bases[shift][i]);
                        engine.reposition(i, bases[shift][i]);   // idle units relocate; busy ones go there after their job
                    }
                }
                default -> throw new IllegalStateException();
            }
        }
        Map<Severity, double[]> rm = new HashMap<>();
        Map<Severity, Double> within = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            List<Double> l = resp.getOrDefault(s, List.of());
            rm.put(s, new double[]{pct(l, 50), pct(l, 90)});
            within.put(s, l.isEmpty() ? Double.NaN : l.stream().filter(x -> x <= s.targetMinutes).count() / (double) l.size());
        }
        rm.put(null, new double[]{pct(allResp, 50), pct(allResp, 90)});
        double qMin = queueWait.isEmpty() ? 0 : queueWait.stream().mapToDouble(x -> x).sum() / allResp.size() / 60.0;
        return new Report(cfg, calls.size(), rm, within, qMin, busySeconds / (cfg.ambulances() * cfg.weeks() * 168 * 3600.0), (System.nanoTime() - wall) / 1e9);
    }

    /**
     * Dynamic move-up ("system status management"): whenever the set of idle units changes, re-solve the coverage
     * problem for the idle units only (busy ones cover nothing), then match idle units to the new bases with the
     * Hungarian algorithm on road travel time so the fleet moves as little as possible. Units already within
     * 90 s of their new base stay put — this avoids ping-ponging crews around the city.
     */
    private void moveUp(double now, PriorityQueue<Ev> q, double busyProb) {
        List<Integer> idle = new ArrayList<>();
        for (var a : engine.ambulances()) if (a.status == com.goldenhour.dispatch.Model.Status.AVAILABLE) idle.add(a.id);
        if (idle.isEmpty()) return;
        List<Integer> pick = CoverageOptimizer.place(covers, staticWeights, idle.size(), busyProb);
        double[][] cost = new double[idle.size()][pick.size()];
        for (int i = 0; i < idle.size(); i++)
            for (int j = 0; j < pick.size(); j++) cost[i][j] = engine.travelSeconds(engine.ambulances().get(idle.get(i)).node, candidates[pick.get(j)]);
        int[] match = Hungarian.solve(cost);
        for (int i = 0; i < idle.size(); i++) {
            int id = idle.get(i), target = candidates[pick.get(match[i])];
            var amb = engine.ambulances().get(id);
            if (amb.baseNode == target) continue;
            engine.moveBase(id, target);
            if (cost[i][match[i]] < 90) { engine.reposition(id, target); continue; }
            moves++;
            q.add(new Ev(now + cost[i][match[i]], BACK_AT_BASE, id, target, ++generation[id]));
        }
    }

    public int lastRunMoves() { return moves; }

    private void start(Assignment as, double now, PriorityQueue<Ev> q, Map<Integer, Incident> incidentOf, Incident inc, double[] busySince) {
        busySince[as.ambulanceId()] = now;
        q.add(new Ev(now + CHUTE + as.etaSeconds(), ON_SCENE, as.ambulanceId(), hospitalIndex(as.hospitalId()), inc));
    }

    private int hospitalIndex(int hospitalId) {
        var hs = engine.hospitals();
        for (int i = 0; i < hs.size(); i++) if (hs.get(i).id == hospitalId) return i;
        return -1;
    }

    private static Incident findQueued(Map<Integer, Incident> incidentOf, Map<String, Integer> callOf, String id) {
        return incidentOf.get(-1 - callOf.get(id));
    }

    private static double pct(List<Double> l, double p) {
        if (l.isEmpty()) return Double.NaN;
        double[] a = l.stream().mapToDouble(x -> x).sorted().toArray();
        return a[Math.min(a.length - 1, (int) Math.ceil(p / 100 * a.length) - 1)];
    }
}
