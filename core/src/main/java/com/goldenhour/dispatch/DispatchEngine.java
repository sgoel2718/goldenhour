package com.goldenhour.dispatch;

import com.goldenhour.dispatch.Model.Ambulance;
import com.goldenhour.dispatch.Model.Assignment;
import com.goldenhour.dispatch.Model.Hospital;
import com.goldenhour.dispatch.Model.Incident;
import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.dispatch.Model.Status;
import com.goldenhour.dispatch.Model.Unit;
import com.goldenhour.ds.KdTree;
import com.goldenhour.ds.LruCache;
import com.goldenhour.ds.SpatialGrid;
import com.goldenhour.graph.Geo;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.routing.Dijkstra;
import com.goldenhour.routing.Route;
import com.goldenhour.routing.Router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * The dispatch brain for one region. Single-writer by design: all mutations go through synchronized methods,
 * and regions are sharded across instances with consistent hashing, so no distributed locks are needed.
 *
 * <p><b>Two-stage candidate selection</b> (filter → refine): straight-line k-nearest available ambulances
 * from a {@link SpatialGrid} (O(1) updates as units move), then exact road travel time for those k via the
 * router (contraction hierarchies in production). Straight-line distance alone is wrong on real roads —
 * a river or a one-way system can make the "closest" unit 10 minutes away.
 *
 * <p><b>Hospital choice</b>: for each hospital we precompute ONE backward Dijkstra tree ("travel time from
 * every node to this hospital") at startup, so choosing the best hospital for an incident is O(H) array
 * lookups instead of H route queries.
 */
public final class DispatchEngine {
    public enum Policy { NEAREST_STRAIGHT_LINE, NEAREST_ROAD_ETA }

    private final RoadGraph g;
    private final Router router;
    private final KdTree snap;
    private final SpatialGrid available;
    private final Map<Integer, Ambulance> ambulances = new LinkedHashMap<>();
    private final List<Hospital> hospitals = new ArrayList<>();
    private final List<double[]> timeToHospital = new ArrayList<>();
    private final PriorityQueue<Incident> waiting = new PriorityQueue<>(
        Comparator.comparing(Incident::severity).thenComparingLong(Incident::createdAtMs));
    private final LruCache<Long, Route> routeCache = new LruCache<>(20_000);
    private final Dijkstra oneToMany;
    private Policy policy = Policy.NEAREST_ROAD_ETA;
    private int candidates = 8;

    public DispatchEngine(RoadGraph g, Router router) {
        this.g = g;
        this.router = router;
        double[] lat = new double[g.nodeCount()], lon = new double[g.nodeCount()];
        for (int v = 0; v < g.nodeCount(); v++) { lat[v] = g.lat(v); lon[v] = g.lon(v); }
        snap = new KdTree(lat, lon);
        available = new SpatialGrid(500, g.lat(0));
        oneToMany = new Dijkstra(g);
    }

    // ------------------------------------------------------------------ setup
    public synchronized void setPolicy(Policy p) { policy = p; }
    public synchronized void setCandidates(int k) { candidates = k; }
    public RoadGraph graph() { return g; }
    public int snap(double lat, double lon) { return snap.nearest(lat, lon); }

    public synchronized Ambulance addAmbulance(int id, Unit unit, int node) {
        Ambulance a = new Ambulance(id, unit, node);
        ambulances.put(id, a);
        available.upsert(id, g.lat(node), g.lon(node));
        return a;
    }

    public synchronized void addHospital(Hospital h) {
        hospitals.add(h);
        timeToHospital.add(oneToMany.distancesTo(h.node, Double.POSITIVE_INFINITY));
    }

    public synchronized List<Ambulance> ambulances() { return List.copyOf(ambulances.values()); }
    public synchronized List<Hospital> hospitals() { return List.copyOf(hospitals); }
    public synchronized int waitingCount() { return waiting.size(); }
    public double routeCacheHitRate() { return routeCache.hitRate(); }

    // ------------------------------------------------------------------ dispatch
    /** Assigns the best available unit, or queues the incident (by severity, then age) if none is free. */
    public synchronized Optional<Assignment> dispatch(Incident inc) {
        List<Ambulance> cands = candidatesFor(inc);
        if (cands.isEmpty()) { waiting.add(inc); return Optional.empty(); }
        Ambulance best = null;
        Route bestRoute = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Ambulance a : cands) {
            Route r = route(a.node, inc.node());
            if (!r.found()) continue;
            double score = policy == Policy.NEAREST_STRAIGHT_LINE ? straightLine(a.node, inc.node()) : r.seconds();
            // Critical calls prefer ALS crews: a BLS unit must be >3 min closer to win.
            if (inc.severity() == Severity.P1_CRITICAL && a.unit == Unit.BLS) score += policy == Policy.NEAREST_ROAD_ETA ? 180 : 3000;
            if (score < bestScore) { bestScore = score; best = a; bestRoute = r; }
        }
        if (best == null) { waiting.add(inc); return Optional.empty(); }
        return Optional.of(assign(best, inc, bestRoute, cands.size() + " candidates; " + policy));
    }

    /**
     * Batch dispatch for surges: severity-weighted travel times → Hungarian algorithm → globally optimal matching.
     * Incidents that cannot be matched (more incidents than units) are queued.
     */
    public synchronized List<Assignment> dispatchBatch(List<Incident> incs) {
        List<Ambulance> free = new ArrayList<>();
        for (Ambulance a : ambulances.values()) if (a.status == Status.AVAILABLE) free.add(a);
        List<Assignment> out = new ArrayList<>();
        if (free.isEmpty()) { waiting.addAll(incs); return out; }
        List<Incident> sorted = new ArrayList<>(incs);
        sorted.sort(Comparator.comparing(Incident::severity));
        List<Incident> now = sorted.subList(0, Math.min(sorted.size(), free.size()));
        double[][] cost = new double[now.size()][free.size()];
        Route[][] routes = new Route[now.size()][free.size()];
        for (int i = 0; i < now.size(); i++)
            for (int j = 0; j < free.size(); j++) {
                routes[i][j] = route(free.get(j).node, now.get(i).node());
                cost[i][j] = routes[i][j].found() ? routes[i][j].seconds() * now.get(i).severity().weight : 1e12;
            }
        int[] match = Hungarian.solve(cost);
        for (int i = 0; i < now.size(); i++)
            out.add(assign(free.get(match[i]), now.get(i), routes[i][match[i]], "batch (Hungarian) over " + now.size() + "x" + free.size()));
        for (int i = now.size(); i < sorted.size(); i++) waiting.add(sorted.get(i));
        return out;
    }

    /** Unit finished a job at {@code node}. Serves the oldest most-severe waiting incident first, if any. */
    public synchronized Optional<Assignment> release(int ambulanceId, int node) {
        Ambulance a = ambulances.get(ambulanceId);
        a.node = node;
        a.status = Status.AVAILABLE;
        a.incidentId = null;
        available.upsert(a.id, g.lat(node), g.lon(node));
        Incident next = waiting.poll();
        if (next == null) return Optional.empty();
        Route r = route(node, next.node());
        return Optional.of(assign(a, next, r, "from queue (waited)"));
    }

    public synchronized void setStatus(int ambulanceId, Status s, int node) {
        Ambulance a = ambulances.get(ambulanceId);
        a.status = s;
        a.node = node;
    }

    public synchronized void moveBase(int ambulanceId, int baseNode) { ambulances.get(ambulanceId).baseNode = baseNode; }

    /** An idle unit drove back to (or was re-positioned at) {@code node}. */
    public synchronized void reposition(int ambulanceId, int node) {
        Ambulance a = ambulances.get(ambulanceId);
        if (a.status != Status.AVAILABLE) return;
        a.node = node;
        available.upsert(a.id, g.lat(node), g.lon(node));
    }

    /** Road travel time between two nodes (cached). */
    public synchronized double travelSeconds(int from, int to) { return route(from, to).seconds(); }

    /** Full route between two nodes (cached), e.g. for the transport leg or the drive back to base. */
    public synchronized Route routeBetween(int from, int to) { return route(from, to); }

    /**
     * Why a unit was chosen: for each candidate the straight-line distance and the real road ETA.
     * Rows {ambulanceId, metres, seconds}. Shown on the dashboard so a dispatcher can audit every decision.
     */
    public synchronized List<double[]> explain(Incident inc) {
        List<double[]> out = new ArrayList<>();
        for (Ambulance a : candidatesFor(inc)) out.add(new double[]{a.id, straightLine(a.node, inc.node()), route(a.node, inc.node()).seconds()});
        return out;
    }

    /** Hospital transport time from {@code node} to hospital h (O(1) via the precomputed reverse tree). */
    public synchronized double timeToHospital(int h, int node) { return timeToHospital.get(h)[node]; }

    // ------------------------------------------------------------------ internals
    private List<Ambulance> candidatesFor(Incident inc) {
        List<Ambulance> out = new ArrayList<>();
        for (int id : available.kNearest(inc.lat(), inc.lon(), candidates)) out.add(ambulances.get(id));
        return out;
    }

    private Assignment assign(Ambulance a, Incident inc, Route r, String reason) {
        a.status = Status.EN_ROUTE;
        a.incidentId = inc.id();
        available.remove(a.id);
        int hIdx = chooseHospital(inc);
        Hospital h = hIdx >= 0 ? hospitals.get(hIdx) : null;
        double transport = h == null ? Double.NaN : timeToHospital.get(hIdx)[inc.node()];
        return new Assignment(inc.id(), a.id, a.unit, r.seconds(), h == null ? -1 : h.id, h == null ? "none" : h.name, transport, r.nodes(), reason);
    }

    private int chooseHospital(Incident inc) {
        int best = -1, fallback = -1;
        double bt = Double.POSITIVE_INFINITY, ft = Double.POSITIVE_INFINITY;
        for (int i = 0; i < hospitals.size(); i++) {
            Hospital h = hospitals.get(i);
            if (h.freeBeds <= 0) continue;
            double t = timeToHospital.get(i)[inc.node()];
            if (t < ft) { ft = t; fallback = i; }
            if ((inc.need() == null || h.specialties.contains(inc.need())) && t < bt) { bt = t; best = i; }
        }
        return best >= 0 ? best : fallback;
    }

    private Route route(int from, int to) {
        long key = (long) from * g.nodeCount() + to;
        Route r = routeCache.get(key);
        if (r == null) { r = router.route(from, to); routeCache.put(key, r); }
        return r;
    }

    private double straightLine(int a, int b) { return Geo.haversineMeters(g.lat(a), g.lon(a), g.lat(b), g.lon(b)); }
}
