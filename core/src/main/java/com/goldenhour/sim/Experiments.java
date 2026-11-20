package com.goldenhour.sim;

import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.dispatch.DispatchEngine.Policy;
import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.routing.ContractionHierarchy;
import com.goldenhour.sim.Simulator.Placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Policy experiments. Every arm replays the SAME call streams (common random numbers), so differences between
 * arms are paired per seed and reported with a 95% confidence interval.
 *
 * <pre>java -jar goldenhour.jar simulate data/city [ambulances=8] [volumeScale=3] [weeks=12] [seeds=10]</pre>
 * Writes reports/simulation.md.
 */
public final class Experiments {
    record Arm(Policy policy, Placement placement, String label) {}

    static final List<Arm> ARMS = List.of(
        new Arm(Policy.NEAREST_STRAIGHT_LINE, Placement.HOSPITAL_BASED, "Status quo: crews wait at hospitals, nearest unit by straight line"),
        new Arm(Policy.NEAREST_STRAIGHT_LINE, Placement.GEOGRAPHIC, "+ coverage-optimised bases (uniform demand)"),
        new Arm(Policy.NEAREST_ROAD_ETA, Placement.GEOGRAPHIC, "+ road-network ETA dispatch (CH routing)"),
        new Arm(Policy.NEAREST_ROAD_ETA, Placement.FORECAST_STATIC, "+ forecast-weighted bases (MEXCLP)"),
        new Arm(Policy.NEAREST_ROAD_ETA, Placement.FORECAST_PER_SHIFT, "  alt: re-optimise every 8 h shift"),
        new Arm(Policy.NEAREST_ROAD_ETA, Placement.DYNAMIC_MOVE_UP, "  alt: dynamic move-up of idle units"));

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path dir = Path.of(args.length > 0 ? args[0] : "data/city");
        int amb = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        double scale = args.length > 2 ? Double.parseDouble(args[2]) : 3.0;
        int weeks = args.length > 3 ? Integer.parseInt(args[3]) : 12;
        int seeds = args.length > 4 ? Integer.parseInt(args[4]) : 10;
        RoadGraph g = RoadGraph.load(dir);
        ContractionHierarchy ch = new ContractionHierarchy(g);

        List<String> md = new ArrayList<>();
        md.add("# Simulation results\n");
        md.add(String.format("Road graph: %,d nodes / %,d directed edges (OpenStreetMap). Fleet %d ambulances (⅓ BLS). Demand: %.1f× the per-capita EMS rate of the real 911 data, %d weeks × %d seeds per arm, identical call streams across arms.%n",
            g.nodeCount(), g.edgeCount(), amb, scale, weeks, seeds));
        md.add("| Configuration | P1 median | P1 p90 | **P1 reached ≤ 8 min** | Δ vs previous step (95% CI) | All-call p90 | Utilisation |");
        md.add("|---|---:|---:|---:|---:|---:|---:|");
        double[][] within = new double[ARMS.size()][seeds];
        for (int a = 0; a < ARMS.size(); a++) {
            double med = 0, p90 = 0, all90 = 0, util = 0;
            for (int s = 0; s < seeds; s++) {
                Simulator.Report r = run(g, ch, dir, amb, ARMS.get(a), scale, weeks, s);
                within[a][s] = r.withinTarget().get(Severity.P1_CRITICAL);
                med += r.responseMinutes().get(Severity.P1_CRITICAL)[0] / seeds;
                p90 += r.responseMinutes().get(Severity.P1_CRITICAL)[1] / seeds;
                all90 += r.responseMinutes().get(null)[1] / seeds;
                util += r.utilisation() / seeds;
            }
            double[] ci = pairedDiff(within[a], within[a >= 4 ? 3 : Math.max(0, a - 1)]);   // alternatives compare to the static forecast
            md.add(String.format("| %s | %.1f min | %.1f min | **%.1f%%** | %s | %.1f min | %.0f%% |", ARMS.get(a).label(), med, p90, 100 * mean(within[a]),
                a == 0 ? "—" : String.format("%+.1f pp (%+.1f … %+.1f)", 100 * ci[0], 100 * (ci[0] - ci[1]), 100 * (ci[0] + ci[1])), all90, 100 * util));
            System.out.println(md.get(md.size() - 1));
        }

        md.add("\n## Fleet size needed\n");
        md.add("Share of P1 calls reached within 8 minutes as the fleet grows (same demand). Reading across a row shows how many ambulances the optimised system saves for the same service level.\n");
        StringBuilder head = new StringBuilder("| Configuration |"), sep = new StringBuilder("|---|");
        int[] fleets = {4, 5, 6, 7, 8, 10};
        for (int f : fleets) { head.append(" ").append(f).append(" |"); sep.append("---:|"); }
        md.add(head.toString()); md.add(sep.toString());
        for (int a : new int[]{0, 1, 2, 3, 5}) {
            StringBuilder row = new StringBuilder("| " + ARMS.get(a).label() + " |");
            for (int f : fleets) {
                double w = 0;
                for (int s = 0; s < Math.min(seeds, 5); s++) w += run(g, ch, dir, f, ARMS.get(a), scale, weeks, s).withinTarget().get(Severity.P1_CRITICAL) / Math.min(seeds, 5);
                row.append(String.format(" %.1f%% |", 100 * w));
            }
            md.add(row.toString());
            System.out.println(row);
        }
        Path out = Path.of(System.getProperty("gh.reports", "reports")).resolve("simulation.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join("\n", md) + "\n");
    }

    static Simulator.Report run(RoadGraph g, ContractionHierarchy ch, Path dir, int amb, Arm arm, double scale, int weeks, int seed) throws Exception {
        DispatchEngine engine = new DispatchEngine(g, ch);
        Scenario sc = Scenario.load(dir, engine);
        sc.addHospitals(engine);
        return new Simulator(engine, sc).run(new Simulator.Config(amb, arm.policy(), arm.placement(), weeks, 4, scale, 1000 + seed));
    }

    static double mean(double[] x) { double s = 0; for (double v : x) s += v; return s / x.length; }

    /** Mean paired difference and 95% half-width (t ≈ 2.26 for 9 d.o.f.; normal approx. otherwise). */
    static double[] pairedDiff(double[] a, double[] b) {
        int n = a.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = a[i] - b[i];
        double m = mean(d), v = 0;
        for (double x : d) v += (x - m) * (x - m);
        double sd = n > 1 ? Math.sqrt(v / (n - 1)) : 0;
        double t = n == 10 ? 2.262 : n == 5 ? 2.776 : 1.96;
        return new double[]{m, t * sd / Math.sqrt(n)};
    }
}
