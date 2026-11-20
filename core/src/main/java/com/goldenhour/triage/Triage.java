package com.goldenhour.triage;

import com.goldenhour.dispatch.Model.Severity;
import com.goldenhour.dispatch.Model.Specialty;
import com.goldenhour.infra.CircuitBreaker;
import com.goldenhour.infra.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a call-taker's free-text notes into a priority, a needed specialty and a category.
 *
 * <p>Primary path: the Python ML service ({@code POST /triage}), which may use an LLM. That call is wrapped in
 * a {@link CircuitBreaker} with a 400 ms timeout; on failure we fall back to deterministic keyword rules.
 * A dispatch decision is never blocked by the ML service — the result records which path produced it.
 */
public final class Triage {
    public record Result(Severity severity, Specialty need, String category, String source) {}

    private record Rule(List<String> words, Severity sev, Specialty need, String category) {}

    // Ordered: the first matching rule wins, most critical first.
    private static final List<Rule> RULES = List.of(
        new Rule(List.of("not breathing", "unconscious", "unresponsive", "cardiac arrest", "no pulse", "choking"), Severity.P1_CRITICAL, Specialty.CARDIAC, "CARDIAC/RESPIRATORY ARREST"),
        new Rule(List.of("chest pain", "heart attack", "cardiac"), Severity.P1_CRITICAL, Specialty.CARDIAC, "CARDIAC EMERGENCY"),
        new Rule(List.of("stroke", "face drooping", "slurred speech", "can't move arm", "cva"), Severity.P1_CRITICAL, Specialty.STROKE, "STROKE"),
        new Rule(List.of("severe bleeding", "hemorrhag", "haemorrhag", "gunshot", "stab"), Severity.P1_CRITICAL, Specialty.TRAUMA, "SEVERE TRAUMA"),
        new Rule(List.of("difficulty breathing", "shortness of breath", "asthma", "respiratory"), Severity.P2_EMERGENT, Specialty.GENERAL, "RESPIRATORY EMERGENCY"),
        new Rule(List.of("burn", "fire", "smoke"), Severity.P2_EMERGENT, Specialty.BURNS, "BURNS"),
        new Rule(List.of("seizure", "convuls", "overdose", "poison", "diabetic", "allergic", "anaphyla"), Severity.P2_EMERGENT, Specialty.GENERAL, "MEDICAL EMERGENCY"),
        new Rule(List.of("labour", "labor", "pregnan", "contractions"), Severity.P2_EMERGENT, Specialty.MATERNITY, "MATERNITY"),
        new Rule(List.of("accident", "collision", "crash", "vehicle", "fall", "fracture", "injur"), Severity.P3_URGENT, Specialty.TRAUMA, "INJURY"),
        new Rule(List.of("fever", "vomit", "abdominal", "headache", "dizz", "weak", "sick"), Severity.P3_URGENT, Specialty.GENERAL, "ILLNESS"));

    private final String mlUrl;
    private final HttpClient http;
    private final CircuitBreaker breaker;

    /** @param mlUrl base URL of the Python service, or null to use rules only */
    public Triage(String mlUrl) {
        this.mlUrl = mlUrl;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)   // no h2c upgrade: uvicorn rejects it
            .connectTimeout(Duration.ofMillis(300)).build();
        this.breaker = new CircuitBreaker(3, 10_000);
    }

    public CircuitBreaker breaker() { return breaker; }

    public Result classify(String notes) {
        if (mlUrl == null) return rules(notes);
        return breaker.call(() -> remote(notes), () -> rules(notes));
    }

    public static Result rules(String notes) {
        String t = notes == null ? "" : notes.toLowerCase();
        for (Rule r : RULES) for (String w : r.words) if (t.contains(w)) return new Result(r.sev, r.need, r.category, "rules");
        return new Result(Severity.P3_URGENT, Specialty.GENERAL, "UNSPECIFIED", "rules");
    }

    private Result remote(String notes) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("notes", notes);
            HttpRequest req = HttpRequest.newBuilder(URI.create(mlUrl + "/triage")).timeout(Duration.ofMillis(400))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("ml status " + res.statusCode());
            Map<String, Object> m = Json.parseObject(res.body());
            return new Result(Severity.valueOf((String) m.get("severity")), Specialty.valueOf((String) m.get("need")), (String) m.get("category"), "ml:" + m.getOrDefault("model", "?"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("ml triage failed: " + e.getMessage(), e);
        }
    }
}
