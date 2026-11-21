package com.goldenhour;

import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.dispatch.Model.Hospital;
import com.goldenhour.dispatch.Model.Incident;
import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.dispatch.Model.Specialty;
import com.goldenhour.dispatch.Model.Unit;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.routing.Dijkstra;
import com.goldenhour.sim.Scenario;
import com.goldenhour.sim.Simulator;
import com.goldenhour.triage.Triage;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static com.goldenhour.Check.equal;
import static com.goldenhour.Check.isTrue;
import static com.goldenhour.Check.near;

public final class DispatchTest {
    /**
     * A line city 0 — 1 — 2 — 3 — 4 where node 4 is geographically closest to the incident at 2 but the only road
     * from 4 is a slow 20-minute detour (a one-way loop). Straight-line dispatch picks the wrong unit; road ETA doesn't.
     */
    static RoadGraph trapCity() {
        double[] lat = {0, 0, 0, 0, 0.0005, 0.03};
        double[] lon = {0, 0.01, 0.02, 0.03, 0.02, 0.02};
        int[] from = {0, 1, 1, 2, 2, 3, 4, 5, 5, 2};
        int[] to   = {1, 0, 2, 1, 3, 2, 5, 2, 4, 5};
        double[] w = {60, 60, 60, 60, 60, 60, 600, 600, 600, 600};
        return RoadGraph.of(lat, lon, from, to, w);
    }

    static Incident inc(String id, int node, Severity s, Specialty need, RoadGraph g) {
        return new Incident(id, g.lat(node), g.lon(node), node, s, need, "test", 0, "");
    }

    public static void testRoadEtaBeatsStraightLine() {
        RoadGraph g = trapCity();
        for (DispatchEngine.Policy p : DispatchEngine.Policy.values()) {
            DispatchEngine e = new DispatchEngine(g, new Dijkstra(g));
            e.setPolicy(p);
            e.addAmbulance(0, Unit.ALS, 0);        // 2 min away by road, 2.2 km
            e.addAmbulance(1, Unit.ALS, 4);        // 55 m away in a straight line, 20 min by road
            var a = e.dispatch(inc("i", 2, Severity.P2_EMERGENT, Specialty.GENERAL, g)).orElseThrow();
            if (p == DispatchEngine.Policy.NEAREST_ROAD_ETA) { equal(0, a.ambulanceId(), "road ETA picks the truly closer unit"); near(120, a.etaSeconds(), 1e-9, "eta"); }
            else equal(1, a.ambulanceId(), "straight line is fooled by the detour");
        }
    }

    public static void testCriticalCallsPreferAlsAndQueueIsSeverityOrdered() {
        RoadGraph g = trapCity();
        DispatchEngine e = new DispatchEngine(g, new Dijkstra(g));
        e.addAmbulance(0, Unit.BLS, 1);            // 1 min away
        e.addAmbulance(1, Unit.ALS, 3);            // 1 min away too: tie → ALS wins for P1
        equal(1, e.dispatch(inc("p1", 2, Severity.P1_CRITICAL, Specialty.CARDIAC, g)).orElseThrow().ambulanceId(), "ALS for P1");
        equal(0, e.dispatch(inc("p3", 2, Severity.P3_URGENT, Specialty.GENERAL, g)).orElseThrow().ambulanceId(), "remaining unit");
        isTrue(e.dispatch(new Incident("late-p3", 0, 0, 0, Severity.P3_URGENT, Specialty.GENERAL, "t", 1, "")).isEmpty(), "queued");
        isTrue(e.dispatch(new Incident("early-p2", 0, 0, 0, Severity.P2_EMERGENT, Specialty.GENERAL, "t", 2, "")).isEmpty(), "queued");
        equal(2, e.waitingCount(), "two waiting");
        equal("early-p2", e.release(0, 1).orElseThrow().incidentId(), "more severe first, even though it arrived later");
        equal("late-p3", e.release(1, 3).orElseThrow().incidentId(), "then the older P3");
    }

    public static void testHospitalChoiceRespectsSpecialty() {
        RoadGraph g = trapCity();
        DispatchEngine e = new DispatchEngine(g, new Dijkstra(g));
        e.addHospital(new Hospital(1, "Near general", 1, EnumSet.of(Specialty.GENERAL), 10));
        e.addHospital(new Hospital(2, "Far stroke unit", 3, EnumSet.of(Specialty.STROKE, Specialty.GENERAL), 10));
        e.addAmbulance(0, Unit.ALS, 0);
        var a = e.dispatch(inc("s", 0, Severity.P1_CRITICAL, Specialty.STROKE, g)).orElseThrow();
        equal("Far stroke unit", a.hospitalName(), "stroke goes to a stroke unit, not the closest door");
        near(180, a.transportSeconds(), 1e-9, "transport via precomputed reverse tree");
    }

    public static void testMinorCasesGoToNearestHospitalAndLongDetoursAreAvoided() {
        RoadGraph g = trapCity();
        DispatchEngine e = new DispatchEngine(g, new Dijkstra(g));
        e.addHospital(new Hospital(1, "Near general", 1, EnumSet.of(Specialty.GENERAL), 10));
        e.addHospital(new Hospital(2, "Far trauma centre", 3, EnumSet.of(Specialty.TRAUMA, Specialty.GENERAL), 10));
        e.addAmbulance(0, Unit.ALS, 0);
        equal("Near general", e.dispatch(inc("sprain", 0, Severity.P3_URGENT, Specialty.TRAUMA, g)).orElseThrow().hospitalName(), "a P3 injury goes to the nearest hospital");
        e.release(0, 0);
        equal("Far trauma centre", e.dispatch(inc("crash", 0, Severity.P1_CRITICAL, Specialty.TRAUMA, g)).orElseThrow().hospitalName(), "a P1 trauma goes to the trauma centre (2 min further)");
    }

        public static void testBatchDispatchIsGloballyOptimal() {
        RoadGraph g = trapCity();
        DispatchEngine e = new DispatchEngine(g, new Dijkstra(g));
        e.addAmbulance(0, Unit.ALS, 1);
        e.addAmbulance(1, Unit.ALS, 3);
        // Greedy (first incident takes its nearest) would send unit 0 to node 2 and unit 1 all the way to node 0.
        var out = e.dispatchBatch(List.of(inc("a", 2, Severity.P2_EMERGENT, Specialty.GENERAL, g), inc("b", 0, Severity.P2_EMERGENT, Specialty.GENERAL, g)));
        double total = out.stream().mapToDouble(x -> x.etaSeconds()).sum();
        near(120, total, 1e-9, "Hungarian finds 60 + 60 instead of 60 + 180");
    }

    public static void testTriageRulesAndBreakerFallback() {
        equal(Severity.P1_CRITICAL, Triage.rules("Man collapsed, NOT BREATHING").severity(), "arrest");
        equal(Specialty.STROKE, Triage.rules("face drooping and slurred speech").need(), "stroke");
        equal(Severity.P3_URGENT, Triage.rules("fell off a ladder, ankle fracture").severity(), "fall");
        Triage t = new Triage("http://127.0.0.1:9");                 // nothing listens there
        for (int i = 0; i < 5; i++) equal("rules", t.classify("chest pain").source(), "falls back to rules");
        equal(com.goldenhour.infra.CircuitBreaker.State.OPEN, t.breaker().state(), "breaker opens after repeated failures");
    }

    /** A fake ML sidecar (JDK HttpServer) proves the remote path, then dies to prove the fallback. */
    public static void testTriageUsesMlServiceThenFallsBackWhenItDies() throws Exception {
        var srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/triage", ex -> {
            byte[] b = "{\"severity\":\"P1_CRITICAL\",\"need\":\"CARDIAC\",\"category\":\"MI\",\"model\":\"fake-llm\"}".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
        Triage t = new Triage("http://127.0.0.1:" + srv.getAddress().getPort());
        var r = t.classify("dad is grey and sweaty, jaw hurts");          // no keyword the rules know
        equal("ml:fake-llm", r.source(), "remote path used");
        equal(Severity.P1_CRITICAL, r.severity(), "remote verdict");
        srv.stop(0);
        equal("rules", t.classify("dad is grey and sweaty, jaw hurts").source(), "service gone → rules");
    }

    public static void testSimulatorSmokeOnRealCity() throws Exception {
        Path dir = Path.of(System.getProperty("gh.data", "../data/city"));
        RoadGraph g = RoadGraph.load(dir);
        var ch = new com.goldenhour.routing.ContractionHierarchy(g);
        double[] within = new double[2];
        int i = 0;
        for (Simulator.Placement p : new Simulator.Placement[]{Simulator.Placement.HOSPITAL_BASED, Simulator.Placement.FORECAST_STATIC}) {
            DispatchEngine e = new DispatchEngine(g, ch);
            Scenario sc = Scenario.load(dir, e);
            sc.addHospitals(e);
            var r = new Simulator(e, sc).run(new Simulator.Config(8, DispatchEngine.Policy.NEAREST_ROAD_ETA, p, 4, 4, 3, 1));
            isTrue(r.calls() > 1000, "calls generated");
            isTrue(r.utilisation() > 0.05 && r.utilisation() < 0.9, "plausible utilisation " + r.utilisation());
            within[i++] = r.withinTarget().get(Severity.P1_CRITICAL);
        }
        isTrue(within[1] > within[0] + 0.2, "optimised placement clearly beats the status quo: " + within[0] + " → " + within[1]);
    }
}
