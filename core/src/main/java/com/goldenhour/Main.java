package com.goldenhour;

import com.goldenhour.api.Server;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Entry point. {@code serve} (default) runs the API + dashboard; {@code simulate} runs the policy experiments;
 * {@code bench} runs the routing/dispatch micro-benchmarks. Configuration comes from environment variables so the
 * same jar runs locally, in Docker and on Hugging Face Spaces.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String cmd = args.length > 0 ? args[0] : "serve";
        String[] rest = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (cmd) {
            case "serve" -> {
                Server s = new Server(Path.of(env("GH_DATA", "data/chennai")), Path.of(env("GH_WEB", "web")).toAbsolutePath().normalize(),
                    Path.of(env("GH_REPORTS", "reports")), Path.of(env("GH_WAL", "data/wal/events.log")), System.getenv("GH_ML_URL"),
                    Integer.parseInt(env("GH_AMBULANCES", "20")), Double.parseDouble(env("GH_SPEED", "1")), Double.parseDouble(env("GH_VOLUME", "0.35")),
                    Boolean.parseBoolean(env("GH_AUTO_CALLS", "true")));
                s.start(Integer.parseInt(env("PORT", env("GH_PORT", "8080"))));
            }
            case "simulate" -> com.goldenhour.sim.Experiments.main(rest);
            case "bench" -> Benchmark.main(rest);
            case "export-static" -> com.goldenhour.tools.ExportStatic.main(rest);
            default -> { System.err.println("usage: serve | simulate [dir amb scale weeks seeds] | bench [dir]"); System.exit(2); }
        }
    }

    static String env(String k, String def) { String v = System.getenv(k); return v == null || v.isBlank() ? def : v; }
}
