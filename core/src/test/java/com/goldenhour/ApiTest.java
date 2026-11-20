package com.goldenhour;

import com.goldenhour.api.Server;
import com.goldenhour.infra.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.goldenhour.Check.equal;
import static com.goldenhour.Check.isTrue;

/** Boots the real server on an ephemeral port and exercises the public API over HTTP. */
public final class ApiTest {
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    static HttpResponse<String> call(String method, String url, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    public static void testIncidentLifecycleIdempotencyRateLimitAndRouting() throws Exception {
        Path tmp = Files.createTempDirectory("gh");
        Files.writeString(tmp.resolve("index.html"), "<h1>dash</h1>");
        Path data = Path.of(System.getProperty("gh.data", "../data/city"));
        Server s = new Server(data, tmp, tmp, tmp.resolve("wal.log"), null, 6, 30, 3, false);
        var srv = s.start(0);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        try {
            equal(200, call("GET", base + "/health", null).statusCode(), "health");
            equal(200, call("GET", base + "/", null).statusCode(), "dashboard served");
            equal(404, call("GET", base + "/../../etc/passwd", null).statusCode(), "no path traversal");

            String body = "{\"lat\":42.507,\"lon\":1.521,\"notes\":\"unconscious man, not breathing\"}";
            var r1 = call("POST", base + "/api/v1/incidents", body, "Idempotency-Key", "k1", "Content-Type", "application/json");
            equal(201, r1.statusCode(), "dispatched");
            Map<String, Object> res = Json.parseObject(r1.body());
            equal("P1_CRITICAL", ((Map<String, Object>) res.get("triage")).get("severity"), "triaged");
            Map<String, Object> as = (Map<String, Object>) res.get("assignment");
            isTrue(((Number) as.get("etaMin")).doubleValue() < 30 && !((List<Object>) as.get("path")).isEmpty(), "route returned");

            var r2 = call("POST", base + "/api/v1/incidents", body, "Idempotency-Key", "k1");
            equal(200, r2.statusCode(), "retry is a replay");
            equal(r1.body(), r2.body(), "same response, no second ambulance");
            equal(1, Json.parseObject(call("GET", base + "/api/v1/state", null).body()).get("stats") instanceof Map<?, ?> st ? ((Number) st.get("calls")).intValue() : -1, "one incident recorded");
            isTrue(Files.readAllLines(tmp.resolve("wal.log")).size() == 1, "journaled once");

            equal(400, call("POST", base + "/api/v1/incidents", "{\"lat\":\"x\"}").statusCode(), "validation");

            Map<String, Object> ch = Json.parseObject(call("GET", base + "/api/v1/route?from=42.507,1.521&to=42.56,1.65&algo=ch", null).body());
            Map<String, Object> dj = Json.parseObject(call("GET", base + "/api/v1/route?from=42.507,1.521&to=42.56,1.65&algo=dijkstra", null).body());
            equal(((Number) dj.get("minutes")).doubleValue(), ((Number) ch.get("minutes")).doubleValue(), "CH is exact");
            isTrue(((Number) ch.get("settledNodes")).intValue() * 20 < ((Number) dj.get("settledNodes")).intValue(), "CH searches far less");

            int limited = 0;
            for (int i = 0; i < 20; i++) if (call("POST", base + "/api/v1/incidents", body).statusCode() == 429) limited++;
            isTrue(limited > 0, "write rate limit enforced");
            String metrics = call("GET", base + "/metrics", null).body();
            isTrue(metrics.contains("http_rate_limited_total") && metrics.contains("dispatch_latency"), "metrics exposed");
        } finally {
            srv.stop(0);
        }
    }
}
