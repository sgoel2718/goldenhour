package com.goldenhour.api;

import com.goldenhour.dispatch.DispatchEngine;
import com.goldenhour.ds.LruCache;
import com.goldenhour.ds.TokenBucket;
import com.goldenhour.graph.RoadGraph;
import com.goldenhour.infra.Json;
import com.goldenhour.infra.Metrics;
import com.goldenhour.infra.WriteAheadLog;
import com.goldenhour.routing.AStar;
import com.goldenhour.routing.BidirectionalDijkstra;
import com.goldenhour.routing.ContractionHierarchy;
import com.goldenhour.routing.Dijkstra;
import com.goldenhour.routing.Route;
import com.goldenhour.routing.Router;
import com.goldenhour.sim.Scenario;
import com.goldenhour.sim.Simulator;
import com.goldenhour.triage.Triage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HTTP API on the JDK's built-in server (no framework: the whole service starts in ~1 s and ships as one jar).
 *
 * <pre>
 * POST /api/v1/incidents        {lat, lon, notes}   Idempotency-Key header supported   → 201 dispatched | 202 queued
 * GET  /api/v1/state            fleet, incidents, hospitals, live stats, decision log
 * GET  /api/v1/route?from=lat,lon&to=lat,lon&algo=ch|dijkstra|astar|bidi   → path + settled nodes + latency
 * GET  /api/v1/demand           forecast calls per zone for the current hour (heat map)
 * POST /api/v1/sim              {autoCalls: bool}   toggle the synthetic call stream
 * GET  /api/v1/reports          simulation + forecast results (markdown / json)
 * GET  /health, /metrics        liveness, Prometheus metrics
 * </pre>
 * Writes are rate-limited per client (token bucket → 429), journaled to a write-ahead log before acknowledging,
 * and idempotent: a retried POST with the same key returns the original response instead of a second ambulance.
 */
public final class Server {
    private final RoadGraph g;
    private final DispatchEngine engine;
    private final LiveFleet fleet;
    private final Scenario scenario;
    private final Metrics metrics = new Metrics();
    private final TokenBucket writeLimit = new TokenBucket(10, 1.0);
    private final TokenBucket readLimit = new TokenBucket(60, 20.0);
    private final LruCache<String, String> idempotent = new LruCache<>(10_000);
    private final WriteAheadLog wal;
    private final Map<String, Router> routers = new LinkedHashMap<>();
    private final Path web, reports;
    private final long started = System.currentTimeMillis();
    private final double chBuildSeconds;
    private final int replayed;

    public Server(Path data, Path web, Path reports, Path walPath, String mlUrl, int ambulances, double speed, double volume, boolean autoCalls) throws IOException {
        this.web = web;
        this.reports = reports;
        g = RoadGraph.load(data);
        long t0 = System.nanoTime();
        ContractionHierarchy ch = new ContractionHierarchy(g);
        chBuildSeconds = (System.nanoTime() - t0) / 1e9;
        routers.put("ch", new ContractionHierarchy(g));
        routers.put("dijkstra", new Dijkstra(g));
        routers.put("astar", new AStar(g));
        routers.put("bidi", new BidirectionalDijkstra(g));
        engine = new DispatchEngine(g, ch);
        scenario = Scenario.load(data, engine);
        scenario.addHospitals(engine);
        int[] bases = new Simulator(engine, scenario).basesFor(Simulator.Placement.FORECAST_STATIC, ambulances, volume, 7);
        for (int i = 0; i < ambulances; i++) engine.addAmbulance(i, i % 3 == 2 ? com.goldenhour.dispatch.Model.Unit.BLS : com.goldenhour.dispatch.Model.Unit.ALS, bases[i]);
        replayed = WriteAheadLog.replay(walPath).size();
        wal = new WriteAheadLog(walPath, false);
        fleet = new LiveFleet(engine, scenario, new Triage(mlUrl), metrics, speed, volume, autoCalls);
    }

    public HttpServer start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 256);
        http.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2), r -> { Thread t = new Thread(r, "http"); t.setDaemon(true); return t; }));
        http.createContext("/", this::handle);
        http.start();
        ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "sim-clock"); t.setDaemon(true); return t; });
        clock.scheduleAtFixedRate(() -> {
            try { fleet.tick(0.2 * fleet.speed()); } catch (Throwable e) { e.printStackTrace(); }
        }, 200, 200, TimeUnit.MILLISECONDS);
        System.out.printf("GoldenHour listening on :%d — %,d road nodes, CH built in %.2f s, %d ambulances, %d WAL events replayed%n",
            port, g.nodeCount(), chBuildSeconds, engine.ambulances().size(), replayed);
        return http;
    }

    // ------------------------------------------------------------------ routing table
    private void handle(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        String client = clientOf(ex);
        int status = 500;
        try {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            if (method.equals("OPTIONS")) {
                ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                status = send(ex, 204, "", null);
                return;
            }
            boolean write = method.equals("POST");
            if (path.startsWith("/api/") && !(write ? writeLimit : readLimit).tryAcquire(client)) {
                metrics.inc("http_rate_limited_total");
                ex.getResponseHeaders().add("Retry-After", "1");
                status = json(ex, 429, Map.of("error", "rate limit exceeded", "retryAfterSeconds", 1));
                return;
            }
            status = switch (method + " " + path) {
                case "GET /health" -> json(ex, 200, Map.of("status", "ok", "uptimeSeconds", (System.currentTimeMillis() - started) / 1000));
                case "GET /metrics" -> send(ex, 200, prometheus(), "text/plain; version=0.0.4");
                case "GET /api/v1/state" -> json(ex, 200, fleet.state());
                case "POST /api/v1/incidents" -> postIncident(ex, client);
                case "GET /api/v1/route" -> route(ex);
                case "GET /api/v1/demand" -> demand(ex);
                case "GET /api/v1/info" -> json(ex, 200, info());
                case "POST /api/v1/sim" -> sim(ex);
                case "GET /api/v1/reports" -> reports(ex);
                default -> method.equals("GET") ? staticFile(ex, path) : json(ex, 404, Map.of("error", "not found"));
            };
        } catch (BadRequest e) {
            status = json(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            status = json(ex, 500, Map.of("error", "internal error"));
        } finally {
            metrics.inc("http_requests_total{status=\"" + status + "\"}");
            if (path.startsWith("/api/")) metrics.observeMs("http_latency", (System.nanoTime() - t0) / 1e6);
            ex.close();
        }
    }

    static final class BadRequest extends RuntimeException { BadRequest(String m) { super(m); } }

    private int postIncident(HttpExchange ex, String client) throws IOException {
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        if (key != null) {
            String prev = idempotent.get(client + "|" + key);
            if (prev != null) { metrics.inc("incidents_idempotent_replay"); ex.getResponseHeaders().add("Idempotent-Replay", "true"); return send(ex, 200, prev, "application/json"); }
        }
        Map<String, Object> body;
        try { body = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new BadRequest("body must be a JSON object"); }
        double lat = num(body, "lat"), lon = num(body, "lon");
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new BadRequest("lat/lon out of range");
        Object n = body.get("notes");
        String notes = n == null ? "" : n.toString();
        if (notes.length() > 1000) throw new BadRequest("notes too long");
        Map<String, Object> res = fleet.report(lat, lon, notes, "api");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "incident"); event.put("at", System.currentTimeMillis()); event.put("lat", lat); event.put("lon", lon); event.put("notes", notes);
        event.put("result", res.get("triage"));
        wal.append(Json.write(event));                       // durable before we acknowledge
        String out = Json.write(res);
        if (key != null) idempotent.put(client + "|" + key, out);
        return send(ex, res.get("assignment") == null ? 202 : 201, out, "application/json");
    }

    private int route(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        double[] a = latLon(q.get("from")), b = latLon(q.get("to"));
        String algo = q.getOrDefault("algo", "ch");
        Router r = routers.get(algo);
        if (r == null) throw new BadRequest("algo must be one of " + routers.keySet());
        int s = engine.snap(a[0], a[1]), t = engine.snap(b[0], b[1]);
        Route route;
        long t0 = System.nanoTime();
        synchronized (r) { route = r.route(s, t); }        // routers reuse scratch arrays: one query at a time each
        double ms = (System.nanoTime() - t0) / 1e6;
        metrics.observeMs("route_latency_" + algo, ms);
        List<Object> path = new java.util.ArrayList<>();
        for (int v : route.nodes()) path.add(List.of(g.lat(v), g.lon(v)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", r.name()); out.put("found", route.found()); out.put("minutes", route.seconds() / 60);
        out.put("settledNodes", route.settled()); out.put("queryMs", ms); out.put("path", path);
        return json(ex, 200, out);
    }

    private int demand(HttpExchange ex) throws IOException {
        int how = (int) (fleet.now() / 3600) % 168;
        List<Object> zones = new java.util.ArrayList<>();
        for (Scenario.Zone z : scenario.zones) {
            double week = 0;
            for (double r : z.ratePerHour()) week += r;
            zones.add(Map.of("id", z.id(), "lat", g.lat(z.node()), "lon", g.lon(z.node()), "nowPerHour", z.ratePerHour()[how], "perWeek", week));
        }
        return json(ex, 200, Map.of("hourOfWeek", how, "zones", zones));
    }

    private int sim(HttpExchange ex) throws IOException {
        Map<String, Object> body = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (body.get("autoCalls") instanceof Boolean on) fleet.setAutoCalls(on);
        return json(ex, 200, Map.of("autoCalls", fleet.autoCalls()));
    }

    private int reports(HttpExchange ex) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Path sim = reports.resolve("simulation.md"), fc = reports.resolve("forecast_metrics.json"), bench = reports.resolve("benchmarks.md");
        out.put("simulation", Files.exists(sim) ? Files.readString(sim) : null);
        out.put("benchmarks", Files.exists(bench) ? Files.readString(bench) : null);
        out.put("forecast", Files.exists(fc) ? Json.parse(Files.readString(fc)) : null);
        return json(ex, 200, out);
    }

    private Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", g.nodeCount()); m.put("edges", g.edgeCount()); m.put("chBuildSeconds", chBuildSeconds);
        m.put("zones", scenario.zones.size()); m.put("hospitals", engine.hospitals().size()); m.put("ambulances", engine.ambulances().size());
        m.put("walEventsReplayed", replayed); m.put("walEventsAppended", wal.appended()); m.put("speed", fleet.speed());
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int v = 0; v < g.nodeCount(); v++) { minLat = Math.min(minLat, g.lat(v)); maxLat = Math.max(maxLat, g.lat(v)); minLon = Math.min(minLon, g.lon(v)); maxLon = Math.max(maxLon, g.lon(v)); }
        m.put("bounds", List.of(List.of(minLat, minLon), List.of(maxLat, maxLon)));
        return m;
    }

    private String prometheus() {
        StringBuilder sb = new StringBuilder(metrics.prometheus());
        sb.append("goldenhour_ambulances ").append(engine.ambulances().size()).append('\n');
        sb.append("goldenhour_waiting_incidents ").append(engine.waitingCount()).append('\n');
        sb.append("goldenhour_route_cache_hit_ratio ").append(String.format(java.util.Locale.ROOT, "%.4f", engine.routeCacheHitRate())).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ static dashboard
    private int staticFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        Path f = web.resolve(path.substring(1)).normalize();
        if (!f.startsWith(web) || !Files.isRegularFile(f)) return json(ex, 404, Map.of("error", "not found"));   // no path traversal
        String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css"
            : path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".json") ? "application/json" : "application/octet-stream";
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        return 200;
    }

    // ------------------------------------------------------------------ helpers
    private static String clientOf(HttpExchange ex) {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");      // behind the Spaces / cloud proxy
        return fwd != null ? fwd.split(",")[0].trim() : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static int json(HttpExchange ex, int code, Object o) throws IOException { return send(ex, code, Json.write(o), "application/json"); }

    private static int send(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (type != null) ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, code == 204 ? -1 : b.length);
        if (code != 204) try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        return code;
    }

    private static double num(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof Number n)) throw new BadRequest("'" + k + "' must be a number");
        return n.doubleValue();
    }

    private static double[] latLon(String s) {
        if (s == null) throw new BadRequest("from and to are required as lat,lon");
        String[] p = s.split(",");
        try { return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])}; }
        catch (RuntimeException e) { throw new BadRequest("bad coordinate: " + s); }
    }

    private static Map<String, String> query(URI u) {
        Map<String, String> m = new LinkedHashMap<>();
        if (u.getRawQuery() == null) return m;
        for (String kv : u.getRawQuery().split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    public LiveFleet fleet() { return fleet; }
    public DispatchEngine engine() { return engine; }
}
