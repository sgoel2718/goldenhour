package com.goldenhour;

import com.goldenhour.infra.CircuitBreaker;
import com.goldenhour.infra.Json;
import com.goldenhour.infra.Metrics;
import com.goldenhour.infra.WriteAheadLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static com.goldenhour.Check.equal;
import static com.goldenhour.Check.isTrue;

public final class InfraTest {
    public static void testWalReplaysInOrderAndSkipsTornTail() throws Exception {
        Path p = Files.createTempFile("wal", ".log");
        try (WriteAheadLog w = new WriteAheadLog(p, true)) {
            for (int i = 0; i < 100; i++) w.append(Json.write(Map.of("seq", i)));
        }
        Files.writeString(p, "{\"seq\": 100, \"half", StandardOpenOption.APPEND);   // crash mid-write
        List<String> lines = WriteAheadLog.replay(p);
        equal(100, lines.size(), "torn line ignored");
        for (int i = 0; i < 100; i++) equal((long) i, ((Number) Json.parseObject(lines.get(i)).get("seq")).longValue(), "order");
        try (WriteAheadLog w = new WriteAheadLog(p, false)) { w.append("{\"seq\":101}"); }   // reopen appends
        isTrue(WriteAheadLog.replay(p).size() >= 100, "still readable after reopen");
    }

    public static void testCircuitBreakerStateMachine() {
        long[] now = {0};
        CircuitBreaker b = new CircuitBreaker(3, 1000, () -> now[0]);
        int[] calls = {0};
        java.util.function.Supplier<String> failing = () -> { calls[0]++; throw new IllegalStateException("down"); };
        for (int i = 0; i < 3; i++) equal("fallback", b.call(failing, () -> "fallback"), "fallback while failing");
        equal(CircuitBreaker.State.OPEN, b.state(), "opens after threshold");
        b.call(failing, () -> "fallback");
        equal(3, calls[0], "open breaker does not call the dependency");
        now[0] += 1000;
        equal("fallback", b.call(failing, () -> "fallback"), "half-open probe fails");
        equal(CircuitBreaker.State.OPEN, b.state(), "re-opens");
        now[0] += 1000;
        equal("ok", b.call(() -> "ok", () -> "fallback"), "probe succeeds");
        equal(CircuitBreaker.State.CLOSED, b.state(), "closes");
    }

    @SuppressWarnings("unchecked")
    public static void testJsonRoundTrip() {
        String s = "{\"a\":[1,2.5,-3e2,true,false,null],\"b\":{\"c\":\"q\\\"uote\\n\\u00e9\"},\"d\":[]}";
        Map<String, Object> m = Json.parseObject(s);
        List<Object> a = (List<Object>) m.get("a");
        equal(6, a.size(), "array");
        equal(-300.0, ((Number) a.get(2)).doubleValue(), "exponent");
        equal("q\"uote\né", ((Map<String, Object>) m.get("b")).get("c"), "escapes");
        equal(m, Json.parseObject(Json.write(m)), "write ∘ parse is identity");
        equal("42.507123", Json.write(42.507123), "coordinates keep 6 decimals");
        boolean threw = false;
        try { Json.parseObject("{\"a\":1"); } catch (RuntimeException e) { threw = true; }
        isTrue(threw, "rejects malformed input");
    }

    public static void testMetricsExposition() {
        Metrics m = new Metrics();
        for (int i = 0; i < 99; i++) m.observeMs("lat", 0.3);
        m.observeMs("lat", 400);
        m.inc("reqs");
        equal(0.5, m.quantileMs("lat", 0.5), "p50 bucket");
        equal(500.0, m.quantileMs("lat", 1.0), "max bucket");
        String text = m.prometheus();
        isTrue(text.contains("reqs 1") && text.contains("lat_bucket"), "prometheus text format:\n" + text);
    }
}
