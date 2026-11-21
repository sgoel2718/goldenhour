package com.goldenhour.tools;

import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.infra.Json;
import com.goldenhour.routing.ContractionHierarchy;
import com.goldenhour.sim.Scenario;
import com.goldenhour.sim.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the data bundle for the serverless (static) edition of the dashboard: road graph, the
 * contraction hierarchy preprocessed here in Java, hospitals, forecast-optimised bases, demand and case mix.
 * The browser then only runs queries and the live simulation — no server needed.
 *
 * <pre>java -jar goldenhour.jar export-static data/city web/data/city.json [ambulances=8] [volume=3]</pre>
 */
public final class ExportStatic {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "data/chennai");
        Path out = Path.of(args.length > 1 ? args[1] : "web/data/city.json");
        int amb = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        double volume = args.length > 3 ? Double.parseDouble(args[3]) : 0.35;
        RoadGraph g = RoadGraph.load(dir);
        ContractionHierarchy ch = new ContractionHierarchy(g);
        DispatchEngine engine = new DispatchEngine(g, ch);
        Scenario sc = Scenario.load(dir, engine);
        sc.addHospitals(engine);
        int[] bases = new Simulator(engine, sc).basesFor(Simulator.Placement.FORECAST_STATIC, amb, volume, 7);

        int n = g.nodeCount();
        double[] lat = new double[n], lon = new double[n];
        for (int v = 0; v < n; v++) { lat[v] = g.lat(v); lon[v] = g.lon(v); }
        int[] from = new int[g.edgeCount()], to = new int[g.edgeCount()];
        double[] w = new double[g.edgeCount()];
        int k = 0;
        for (int u = 0; u < n; u++) for (int e = g.outBegin(u); e < g.outEnd(u); e++) { from[k] = u; to[k] = g.outTarget(e); w[k++] = g.outWeight(e); }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lat", lat); m.put("lon", lon);
        m.put("edges", Map.of("from", from, "to", to, "w", w));
        m.put("ch", ch.export());
        List<Object> hs = new ArrayList<>();
        for (var h : engine.hospitals()) hs.add(Map.of("id", h.id, "name", h.name, "node", h.node, "specialties", new ArrayList<>(h.specialties), "phone", sc.phoneOf(h.id)));
        m.put("hospitals", hs);
        m.put("bases", bases);
        List<Object> zones = new ArrayList<>();
        for (Scenario.Zone z : sc.zones) zones.add(Map.of("id", z.id(), "node", z.node(), "rates", z.ratePerHour()));
        m.put("zones", zones);
        List<Object> cm = new ArrayList<>();
        for (Scenario.CaseType c : sc.caseMix) cm.add(Map.of("category", c.category(), "severity", c.severity(), "need", c.need(), "share", c.share()));
        m.put("caseMix", cm);
        m.put("volume", volume); m.put("city", sc.name); m.put("tz", sc.timeZone);
        m.put("shortcuts", ch.shortcutCount());
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, Json.write(m));
        System.out.printf("wrote %s (%.1f MB): %,d nodes, %,d edges, %,d CH edges, %d hospitals, %d bases, %d zones%n",
            out, Files.size(out) / 1e6, n, g.edgeCount(), ((int[]) ch.export().get("from")).length, hs.size(), bases.length, zones.size());
    }
}
