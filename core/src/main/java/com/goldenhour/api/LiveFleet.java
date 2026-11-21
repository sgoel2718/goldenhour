package com.goldenhour.api;

import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.dispatch.Model.Ambulance;
import com.goldenhour.dispatch.Model.Assignment;
import com.goldenhour.dispatch.Model.Incident;
import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.dispatch.Model.Status;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.infra.Metrics;
import com.goldenhour.routing.Route;
import com.goldenhour.sim.Scenario;
import com.goldenhour.triage.Triage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The live system behind the dashboard: a clock that starts at the city's real local time and runs at {@code speed}× (1 = real time),
 * ambulances that physically drive their routes (their position is interpolated along the road geometry and
 * pushed into the dispatcher's spatial index as they move), and an optional synthetic call stream drawn from
 * the forecast demand model so the demo is never idle.
 *
 * <p>Job lifecycle: EN_ROUTE → ON_SCENE (12–20 min) → TRANSPORTING (to the chosen hospital) → HANDOVER (15 min)
 * → RETURNING to base (dispatchable while returning) → AVAILABLE at base.
 */
public final class LiveFleet {
    enum Phase { EN_ROUTE, ON_SCENE, TRANSPORTING, HANDOVER, RETURNING }

    static final class Job {
        Phase phase;
        Incident inc;
        Assignment as;
        List<Integer> route = List.of();
        double[] cum = new double[0];      // cumulative seconds along route
        double start, end;                 // sim seconds
    }

    public record Event(double simSeconds, String kind, String text) {}

    private final DispatchEngine engine;
    private final Scenario sc;
    private final Triage triage;
    private final Metrics metrics;
    private final RoadGraph g;
    private final Map<Integer, Job> jobs = new HashMap<>();
    private final Map<String, Incident> incidents = new LinkedHashMap<>();
    private final Map<String, Assignment> assignments = new HashMap<>();
    private final Map<String, Double> responseMin = new HashMap<>();
    private final Deque<Event> events = new ArrayDeque<>();
    private final Random rnd = new Random(42);
    private final double volume;
    private volatile double speed;
    private volatile long wallStart = System.nanoTime();
    private volatile double simStart;   // seconds since Monday 00:00 of the simulated week (city's local time)
    private volatile boolean autoCalls;
    private int seq;
    private int p1Total, p1Within;
    private final List<Double> allResponses = new ArrayList<>();

    static final Map<String, String> NOTES = Map.ofEntries(
        Map.entry("CARDIAC EMERGENCY", "58 y/o male, crushing chest pain radiating to left arm, sweating"),
        Map.entry("CARDIAC ARREST", "man collapsed at the bakery, not breathing, bystander doing CPR"),
        Map.entry("CVA/STROKE", "wife says husband's face drooping and slurred speech since 20 minutes"),
        Map.entry("RESPIRATORY EMERGENCY", "elderly woman with severe difficulty breathing, lips bluish"),
        Map.entry("FALL VICTIM", "82 y/o fell down the stairs, hip pain, cannot stand"),
        Map.entry("VEHICLE ACCIDENT", "two-car collision on the main road, one driver injured"),
        Map.entry("SEIZURES", "teenager having a seizure, shaking for 3 minutes"),
        Map.entry("UNCONSCIOUS SUBJECT", "person found unconscious in a parked car"),
        Map.entry("UNRESPONSIVE SUBJECT", "father unresponsive in bed, breathing slowly"),
        Map.entry("HEMORRHAGING", "deep cut with severe bleeding from forearm"),
        Map.entry("MATERNITY", "pregnant, 39 weeks, contractions every 3 minutes"),
        Map.entry("OVERDOSE", "suspected overdose, drowsy and vomiting"),
        Map.entry("DIABETIC EMERGENCY", "diabetic, confused and shaky, low sugar reading"),
        Map.entry("ALLERGIC REACTION", "swollen lips after eating nuts, allergic reaction"),
        Map.entry("ABDOMINAL PAINS", "severe abdominal pain and vomiting since morning"),
        Map.entry("HEAD INJURY", "scooter rider hit by a car, head injury, conscious"),
        Map.entry("BURN VICTIM", "kitchen fire, burns to both hands"),
        Map.entry("SUBJECT IN PAIN", "back pain, cannot get out of bed"),
        Map.entry("FEVER", "child with high fever and headache"));

    public LiveFleet(DispatchEngine engine, Scenario sc, Triage triage, Metrics metrics, double speed, double volume, boolean autoCalls) {
        this.engine = engine; this.sc = sc; this.triage = triage; this.metrics = metrics;
        this.g = engine.graph(); this.speed = speed; this.volume = volume; this.autoCalls = autoCalls;
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of(sc.timeZone));
        simStart = ((now.getDayOfWeek().getValue() - 1) * 24 + now.getHour()) * 3600.0 + now.getMinute() * 60.0 + now.getSecond();
    }

    public double now() { return simStart + (System.nanoTime() - wallStart) / 1e9 * speed; }
    public void setAutoCalls(boolean on) { autoCalls = on; }
    public boolean autoCalls() { return autoCalls; }
    public double speed() { return speed; }
    /** Change the clock rate without a jump: re-anchor the simulated time at "now". 1 = real time. */
    public synchronized void setSpeed(double x) { simStart = now(); wallStart = System.nanoTime(); speed = x; }

    // ------------------------------------------------------------------ incidents
    /** Triage → dispatch → start the job. Returns the incident, its assignment (or empty = queued) and the decision audit. */
    public synchronized Map<String, Object> report(double lat, double lon, String notes, String source) {
        long t0 = System.nanoTime();
        Triage.Result tr = triage.classify(notes);
        double triageMs = (System.nanoTime() - t0) / 1e6;
        int node = engine.snap(lat, lon);
        String id = "INC-" + (++seq);
        Incident inc = new Incident(id, lat, lon, node, tr.severity(), tr.need(), tr.category(), (long) (now() * 1000), notes);
        incidents.put(id, inc);
        while (incidents.size() > 300) incidents.remove(incidents.keySet().iterator().next());
        List<double[]> audit = engine.explain(inc);
        long t1 = System.nanoTime();
        var as = engine.dispatch(inc);
        double dispatchMs = (System.nanoTime() - t1) / 1e6;
        metrics.observeMs("dispatch_latency", dispatchMs);
        metrics.inc("incidents_total");
        metrics.inc("incidents_" + tr.severity().name().toLowerCase());
        as.ifPresentOrElse(a -> { assignments.put(id, a); start(a, inc, now()); log("dispatch", id + " " + tr.severity() + " " + tr.category() + " → AMB-" + a.ambulanceId() + " ETA " + mins(a.etaSeconds()) + ", hospital " + a.hospitalName()); },
            () -> { metrics.inc("incidents_queued"); log("queue", id + " " + tr.severity() + " queued — no unit free (" + engine.waitingCount() + " waiting)"); });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incident", incidentJson(inc));
        out.put("triage", Map.of("severity", tr.severity(), "need", tr.need(), "category", tr.category(), "source", tr.source(), "ms", triageMs));
        out.put("assignment", as.map(this::assignmentJson).orElse(null));
        List<Object> cands = new ArrayList<>();
        for (double[] c : audit) cands.add(Map.of("ambulance", (int) c[0], "straightLineKm", c[1] / 1000, "roadEtaMin", c[2] / 60));
        out.put("candidates", cands);
        out.put("dispatchMs", dispatchMs);
        out.put("source", source);
        return out;
    }

    private void start(Assignment a, Incident inc, double t) {
        Job j = new Job();
        j.phase = Phase.EN_ROUTE; j.inc = inc; j.as = a;
        setRoute(j, a.route(), t, 60 + a.etaSeconds());           // 60 s to get rolling ("chute time")
        jobs.put(a.ambulanceId(), j);
    }

    private void setRoute(Job j, List<Integer> nodes, double t, double duration) {
        j.route = nodes;
        j.cum = new double[nodes.size()];
        for (int i = 1; i < nodes.size(); i++) j.cum[i] = j.cum[i - 1] + g.edgeWeight(nodes.get(i - 1), nodes.get(i));
        j.start = t; j.end = t + duration;
    }

    // ------------------------------------------------------------------ clock
    /** Advance the world to the current simulated time. Called by a scheduler every 200 ms. */
    public synchronized void tick(double dtSim) {
        double t = now();
        if (autoCalls) spawn(dtSim);
        for (Map.Entry<Integer, Job> e : new ArrayList<>(jobs.entrySet())) {
            int id = e.getKey();
            Job j = e.getValue();
            while (j != null && t >= j.end) j = advance(id, j, j.end);
            if (j != null && j.phase == Phase.RETURNING) {
                int at = nodeAt(j, t);                       // moving unit: keep the spatial index current
                engine.reposition(id, at);
            }
        }
    }

    private Job advance(int id, Job j, double t) {
        switch (j.phase) {
            case EN_ROUTE -> {
                double r = (t - j.inc.createdAtMs() / 1000.0) / 60.0;
                responseMin.put(j.inc.id(), r);
                allResponses.add(r);
                if (j.inc.severity() == Severity.P1_CRITICAL) { p1Total++; if (r <= 8) p1Within++; }
                metrics.observeMs("response_minutes", r);
                log("scene", "AMB-" + id + " on scene at " + j.inc.id() + " after " + String.format("%.1f", r) + " min");
                j.phase = Phase.ON_SCENE; j.route = List.of(j.inc.node()); j.cum = new double[1];
                j.start = t; j.end = t + 12 * 60 + rnd.nextDouble() * 8 * 60;
            }
            case ON_SCENE -> {
                double p = switch (j.inc.severity()) { case P1_CRITICAL, P2_EMERGENT -> 1.0; case P3_URGENT -> 0.7; case P4_ROUTINE -> 0.3; };
                int h = hospitalIndex(j.as.hospitalId());
                if (h >= 0 && rnd.nextDouble() < p) {
                    Route r = engine.routeBetween(j.inc.node(), engine.hospitals().get(h).node);
                    j.phase = Phase.TRANSPORTING;
                    setRoute(j, r.nodes(), t, r.seconds());
                } else return free(id, j.inc.node(), t);
            }
            case TRANSPORTING -> {
                log("hospital", "AMB-" + id + " delivered " + j.inc.id() + " to " + j.as.hospitalName());
                j.phase = Phase.HANDOVER; int at = j.route.get(j.route.size() - 1);
                j.route = List.of(at); j.cum = new double[1]; j.start = t; j.end = t + 15 * 60;
            }
            case HANDOVER -> { return free(id, j.route.get(0), t); }
            case RETURNING -> { engine.reposition(id, j.route.get(j.route.size() - 1)); jobs.remove(id); return null; }
        }
        return j;
    }

    /** Unit is free at {@code node}: serve the queue first, otherwise drive back to base. */
    private Job free(int id, int node, double t) {
        var next = engine.release(id, node);
        if (next.isPresent()) {
            Assignment a = next.get();
            Incident inc = incidents.get(a.incidentId());
            assignments.put(inc.id(), a);
            log("dispatch", inc.id() + " (queued) → AMB-" + id + " ETA " + mins(a.etaSeconds()));
            start(a, inc, t);
            return jobs.get(id);
        }
        Ambulance amb = engine.ambulances().stream().filter(a -> a.id == id).findFirst().orElseThrow();
        Route back = engine.routeBetween(node, amb.baseNode);
        Job j = new Job();
        j.phase = Phase.RETURNING;
        setRoute(j, back.found() ? back.nodes() : List.of(node), t, back.found() ? back.seconds() : 0);
        jobs.put(id, j);
        return j;
    }

    private void spawn(double dtSim) {
        int how = (int) (now() / 3600) % 168;
        for (Scenario.Zone z : sc.zones) {
            double lambda = z.ratePerHour()[how] * volume * dtSim / 3600.0;
            if (rnd.nextDouble() < lambda) {                        // λ ≪ 1 per tick, so Bernoulli ≈ Poisson
                Scenario.CaseType ct = pickCase();
                String notes = NOTES.getOrDefault(ct.category(), ct.category().toLowerCase().replace('/', ' '));
                double jitter = 0.004;
                report(g.lat(z.node()) + (rnd.nextDouble() - 0.5) * jitter, g.lon(z.node()) + (rnd.nextDouble() - 0.5) * jitter, notes, "simulated");
            }
        }
    }

    private Scenario.CaseType pickCase() {
        double u = rnd.nextDouble(), c = 0;
        for (Scenario.CaseType ct : sc.caseMix) { c += ct.share(); if (u <= c) return ct; }
        return sc.caseMix.get(sc.caseMix.size() - 1);
    }

    // ------------------------------------------------------------------ views
    int nodeAt(Job j, double t) {
        if (j.route.size() <= 1) return j.route.isEmpty() ? -1 : j.route.get(0);
        double along = Math.max(0, t - (j.end - j.cum[j.cum.length - 1]));
        int i = 0;
        while (i + 1 < j.cum.length && j.cum[i + 1] <= along) i++;
        return j.route.get(i);
    }

    double[] positionAt(Job j, double t) {
        if (j.route.size() <= 1) { int n = j.route.get(0); return new double[]{g.lat(n), g.lon(n)}; }
        double along = Math.max(0, t - (j.end - j.cum[j.cum.length - 1]));
        int i = 0;
        while (i + 1 < j.cum.length && j.cum[i + 1] <= along) i++;
        if (i + 1 >= j.cum.length) { int n = j.route.get(j.route.size() - 1); return new double[]{g.lat(n), g.lon(n)}; }
        double seg = j.cum[i + 1] - j.cum[i], f = seg <= 0 ? 0 : (along - j.cum[i]) / seg;
        int a = j.route.get(i), b = j.route.get(i + 1);
        return new double[]{g.lat(a) + f * (g.lat(b) - g.lat(a)), g.lon(a) + f * (g.lon(b) - g.lon(a))};
    }

    public synchronized Map<String, Object> state() {
        double t = now();
        List<Object> ambs = new ArrayList<>();
        for (Ambulance a : engine.ambulances()) {
            Job j = jobs.get(a.id);
            Map<String, Object> m = new LinkedHashMap<>();
            double[] p = j == null ? new double[]{g.lat(a.node), g.lon(a.node)} : positionAt(j, t);
            m.put("id", a.id); m.put("unit", a.unit); m.put("lat", p[0]); m.put("lon", p[1]);
            m.put("phase", j == null ? "AVAILABLE" : j.phase.name());
            m.put("incident", j == null || j.inc == null ? null : j.inc.id());
            m.put("base", List.of(g.lat(a.baseNode), g.lon(a.baseNode)));
            m.put("secondsLeft", j == null ? 0 : Math.max(0, j.end - t));
            if (j != null && (j.phase == Phase.EN_ROUTE || j.phase == Phase.TRANSPORTING || j.phase == Phase.RETURNING)) m.put("path", remaining(j, t));
            ambs.add(m);
        }
        List<Object> incs = new ArrayList<>();
        List<Incident> recent = new ArrayList<>(incidents.values());
        for (int i = Math.max(0, recent.size() - 40); i < recent.size(); i++) {
            Map<String, Object> m = incidentJson(recent.get(i));
            Assignment a = assignments.get(recent.get(i).id());
            m.put("ambulance", a == null ? null : a.ambulanceId());
            m.put("responseMin", responseMin.get(recent.get(i).id()));
            incs.add(m);
        }
        List<Object> hs = new ArrayList<>();
        for (var h : engine.hospitals()) hs.add(Map.of("id", h.id, "name", h.name, "lat", g.lat(h.node), "lon", g.lon(h.node), "specialties", new ArrayList<>(h.specialties)));
        List<Double> sorted = new ArrayList<>(allResponses);
        sorted.sort(Double::compare);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("calls", (int) metrics.count("incidents_total"));
        stats.put("waiting", engine.waitingCount());
        stats.put("busy", jobs.values().stream().filter(j -> j.phase != Phase.RETURNING).count());
        stats.put("p1Within8", p1Total == 0 ? null : (double) p1Within / p1Total);
        stats.put("p1Count", p1Total);
        stats.put("medianResponseMin", sorted.isEmpty() ? null : sorted.get(sorted.size() / 2));
        stats.put("dispatchP99Ms", metrics.quantileMs("dispatch_latency", 0.99));
        stats.put("routeCacheHitRate", engine.routeCacheHitRate());
        stats.put("triageBreaker", triage.breaker().state());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("simTime", t);
        out.put("clock", clock(t));
        out.put("speed", speed);
        out.put("autoCalls", autoCalls);
        out.put("ambulances", ambs);
        out.put("incidents", incs);
        out.put("hospitals", hs);
        out.put("stats", stats);
        out.put("events", new ArrayList<>(events));
        return out;
    }

    private List<Object> remaining(Job j, double t) {
        List<Object> pts = new ArrayList<>();
        double[] p = positionAt(j, t);
        pts.add(List.of(p[0], p[1]));
        double along = Math.max(0, t - (j.end - j.cum[j.cum.length - 1]));
        int step = Math.max(1, j.route.size() / 150);              // thin long paths for the wire
        for (int i = 0; i < j.route.size(); i += step) if (j.cum[i] > along) pts.add(List.of(g.lat(j.route.get(i)), g.lon(j.route.get(i))));
        int last = j.route.get(j.route.size() - 1);
        pts.add(List.of(g.lat(last), g.lon(last)));
        return pts;
    }

    Map<String, Object> incidentJson(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id()); m.put("lat", i.lat()); m.put("lon", i.lon()); m.put("severity", i.severity()); m.put("need", i.need());
        m.put("category", i.category()); m.put("notes", i.notes()); m.put("time", clock(i.createdAtMs() / 1000.0));
        return m;
    }

    Map<String, Object> assignmentJson(Assignment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ambulance", a.ambulanceId()); m.put("unit", a.unit()); m.put("etaMin", a.etaSeconds() / 60);
        m.put("hospital", a.hospitalName()); m.put("transportMin", a.transportSeconds() / 60); m.put("reason", a.reason());
        List<Object> path = new ArrayList<>();
        int step = Math.max(1, a.route().size() / 200);
        for (int i = 0; i < a.route().size(); i += step) path.add(List.of(g.lat(a.route().get(i)), g.lon(a.route().get(i))));
        if (!a.route().isEmpty()) { int last = a.route().get(a.route().size() - 1); path.add(List.of(g.lat(last), g.lon(last))); }
        m.put("path", path);
        return m;
    }

    private int hospitalIndex(int hospitalId) {
        var hs = engine.hospitals();
        for (int i = 0; i < hs.size(); i++) if (hs.get(i).id == hospitalId) return i;
        return -1;
    }

    private void log(String kind, String text) {
        events.addFirst(new Event(now(), kind, clock(now()) + "  " + text));
        while (events.size() > 60) events.removeLast();
    }

    static String clock(double simSeconds) {
        String[] d = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        long s = (long) simSeconds;
        return String.format("%s %02d:%02d:%02d", d[(int) (s / 86400 % 7)], s / 3600 % 24, s / 60 % 60, s % 60);
    }

    static String mins(double s) { return String.format("%.1f min", s / 60); }

    /** Unused statuses are kept in the model for API completeness; the live view exposes phases instead. */
    static Status statusOf(Phase p) { return p == Phase.RETURNING ? Status.RETURNING : Status.EN_ROUTE; }
}
