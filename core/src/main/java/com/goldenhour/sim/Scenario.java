package com.goldenhour.sim;

import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.dispatch.Model.Hospital;
import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.dispatch.Model.Specialty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * City inputs produced by the Python pipeline:
 * <ul>
 *   <li>{@code facilities.csv} — real hospitals/clinics/stations from OpenStreetMap</li>
 *   <li>{@code demand.csv} — expected calls per zone per hour-of-week (zone, lat, lon, how, rate)</li>
 *   <li>{@code case_mix.csv} — call categories with priority, specialty and share (learned from real 911 EMS calls)</li>
 * </ul>
 */
public final class Scenario {
    public record Zone(String id, int node, double[] ratePerHour) {}          // ratePerHour[168]
    public record CaseType(String category, Severity severity, Specialty need, double share) {}

    public final List<Zone> zones = new ArrayList<>();
    public final List<CaseType> caseMix = new ArrayList<>();
    public final List<double[]> hospitals = new ArrayList<>();                 // {lat, lon}
    /** Named hospitals with known capabilities (optional {@code hospitals.csv}); otherwise OSM facilities are used. */
    public final List<String> hospitalNames = new ArrayList<>();
    public final List<EnumSet<Specialty>> hospitalSpecs = new ArrayList<>();
    public String name = "City", timeZone = "UTC";

    public static Scenario load(Path dir, DispatchEngine engine) throws IOException {
        Scenario s = new Scenario();
        java.util.Map<String, Zone> zones = new java.util.LinkedHashMap<>();
        for (String[] r : rows(dir.resolve("demand.csv"))) {
            Zone z = zones.computeIfAbsent(r[0], id -> new Zone(id, engine.snap(Double.parseDouble(r[1]), Double.parseDouble(r[2])), new double[168]));
            z.ratePerHour()[Integer.parseInt(r[3])] = Double.parseDouble(r[4]);
        }
        s.zones.addAll(zones.values());
        for (String[] r : rows(dir.resolve("case_mix.csv")))
            s.caseMix.add(new CaseType(r[0], Severity.valueOf(r[1]), Specialty.valueOf(r[2]), Double.parseDouble(r[3])));
        Path named = dir.resolve("hospitals.csv");
        if (Files.exists(named)) {
            for (String[] r : rows(named)) {
                s.hospitals.add(new double[]{Double.parseDouble(r[1]), Double.parseDouble(r[2])});
                s.hospitalNames.add(r[0]);
                EnumSet<Specialty> set = EnumSet.noneOf(Specialty.class);
                for (String x : r[3].split("\\|")) set.add(Specialty.valueOf(x.trim()));
                s.hospitalSpecs.add(set);
            }
        } else {
            for (String[] r : rows(dir.resolve("facilities.csv")))
                if (r[0].equals("hospital")) s.hospitals.add(new double[]{Double.parseDouble(r[r.length - 2]), Double.parseDouble(r[r.length - 1])});
        }
        Path nm = dir.resolve("name.txt");
        if (Files.exists(nm)) {
            List<String> l = Files.readAllLines(nm);
            s.name = l.get(0).trim();
            if (l.size() > 1 && !l.get(1).isBlank()) s.timeZone = l.get(1).trim();
        }
        return s;
    }

    /**
     * Registers hospitals with the engine. OSM rarely tags clinical capabilities, so we assume the hospital
     * closest to the demand-weighted centre of the city is the full-service regional hospital (cardiac, stroke,
     * trauma, maternity) and smaller sites get a rotating subset. Replace with real capability data in production.
     */
    public void addHospitals(DispatchEngine engine) {
        if (!hospitalNames.isEmpty()) {
            for (int i = 0; i < hospitals.size(); i++)
                engine.addHospital(new Hospital(i, hospitalNames.get(i), engine.snap(hospitals.get(i)[0], hospitals.get(i)[1]), hospitalSpecs.get(i), 1_000_000));
            return;
        }
        Specialty[][] specs = {
            {Specialty.GENERAL, Specialty.TRAUMA, Specialty.BURNS}, {Specialty.GENERAL, Specialty.MATERNITY}, {Specialty.GENERAL, Specialty.CARDIAC}};
        double cl = 0, co = 0, w = 0;
        for (Zone z : zones) {
            double r = 0;
            for (double x : z.ratePerHour()) r += x;
            cl += r * engine.graph().lat(z.node()); co += r * engine.graph().lon(z.node()); w += r;
        }
        int main = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < hospitals.size(); i++) {
            double d = com.goldenhour.graph.Geo.haversineMeters(hospitals.get(i)[0], hospitals.get(i)[1], cl / w, co / w);
            if (d < best) { best = d; main = i; }
        }
        for (int i = 0, k = 0; i < hospitals.size(); i++) {
            double[] h = hospitals.get(i);
            EnumSet<Specialty> set = EnumSet.noneOf(Specialty.class);
            if (i == main) set = EnumSet.allOf(Specialty.class);
            else java.util.Collections.addAll(set, specs[k++ % specs.length]);
            String name = i == main ? "Regional Hospital" : "Hospital " + (char) ('A' + (i < main ? i : i - 1));
            engine.addHospital(new Hospital(i, name, engine.snap(h[0], h[1]), set, 1_000_000));
        }
    }

    public double totalRatePerWeek() {
        double t = 0;
        for (Zone z : zones) for (double r : z.ratePerHour()) t += r;
        return t;
    }

    private static List<String[]> rows(Path p) throws IOException {
        List<String[]> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) if (!lines.get(i).isBlank()) out.add(lines.get(i).split(","));
        return out;
    }
}
