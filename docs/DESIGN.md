# GoldenHour: design document

## 1. Problem

An emergency medical services (EMS) system has to make two decisions over and over:

1. **Dispatch.** A call arrives. Which ambulance goes, and to which hospital?
2. **Positioning.** While no call is active, where should each idle crew wait?

Most systems answer (1) with "nearest unit by straight line" and (2) with "at the hospital or station". Both are cheap. Both are wrong in measurable ways on real road networks (see [reports/simulation.md](../reports/simulation.md)). GoldenHour makes both decisions with real road travel times and a demand forecast, under hard latency and availability constraints.

## 2. Requirements

**Functional**
- Accept an incident (location and free-text notes). Triage it into a priority and the hospital capability it needs.
- Assign the best available unit within milliseconds, or queue the call by severity and then age. Return the route and ETA.
- Choose a destination hospital that has the needed specialty (stroke, cardiac, trauma, burns, maternity).
- Track the fleet in real time. Re-plan base positions from the demand forecast.
- Allow every decision to be audited. For each candidate unit, show its straight-line distance and road ETA, and why it won or lost.

**Non-functional**

| Property | Target | Why |
|---|---|---|
| Dispatch latency | p99 < 50 ms server-side (measured 0.4 ms) | Seconds matter, and the call-taker is waiting |
| Availability | 99.99% for dispatch | An outage costs lives. The ML is *never* on the critical path |
| Correctness | Exact shortest paths. No incident lost or dispatched twice | A wrong ETA sends the wrong unit. A double dispatch strands another patient |
| Durability | Acknowledge only after the incident is in the WAL | A crash must not drop a call |
| Explainability | Every decision can be audited | Medical-legal review |

## 3. Capacity estimate (country scale)

| Quantity | Estimate |
|---|---|
| Population served | 1.4 B |
| EMS calls per person per year | ≈ 0.1 |
| Calls / year | ≈ 140 M → **≈ 4.5 / s average, ≈ 45 / s peak** (10× surge: festival, disaster) |
| Ambulances | ≈ 50,000 |
| GPS pings | every 5 s → **10,000 writes / s** |
| Routing per dispatch | ~8 CH queries × 15 µs ≈ 0.12 ms |
| Road graph per region | 1–5 M nodes → CSR + CH ≈ 0.3–1.5 GB RAM |

**Conclusion:** throughput is small. The hard parts are **tail latency, correctness under concurrency, and availability**. That is why the core is an in-memory, single-writer engine per region and not a distributed database transaction per dispatch.

## 4. API

```
POST /api/v1/incidents   {lat, lon, notes}   Idempotency-Key: <uuid>
     201 {incident, triage, assignment{ambulance, etaMin, hospital, path}, candidates[]}
     202 queued  ·  400 invalid  ·  429 rate limited (Retry-After)
GET  /api/v1/state                fleet positions and phases, recent incidents, live KPIs, decision log
GET  /api/v1/route?from&to&algo   exact route, settled nodes, latency (routing lab)
GET  /api/v1/demand               forecast calls per zone for the current hour
GET  /metrics  /health
```

A retried POST with the same idempotency key returns the original response. The network can drop the response, but the second ambulance is never sent.

## 5. Data model

| Entity | Key fields | Store |
|---|---|---|
| Incident | id, lat/lon, snapped node, severity, need, category, createdAt, notes | WAL (source of truth), then the analytics warehouse |
| Assignment | incidentId, ambulanceId, ETA, route, hospitalId, reason | WAL |
| Ambulance | id, unit (ALS/BLS), node, baseNode, status | In-memory, in the region's single writer. Rebuilt from GPS and the WAL |
| Hospital | id, node, specialties, freeBeds | Config, plus bed feed |
| Road graph | CSR arrays and the CH overlay | Immutable file, rebuilt nightly from OSM and traffic |
| Demand forecast | zone × hour-of-week rate | Recomputed weekly by the ML pipeline |

## 6. Architecture and key decisions

### 6.1 Single writer per region (no distributed locks)
Each region's engine owns its fleet. All mutations are `synchronized` inside one JVM, which gives a total order of decisions without distributed locks or consensus on the hot path. Regions are placed on nodes by **consistent hashing with virtual nodes** (`ConsistentHashRing`). Adding a node moves only about 1/N of the regions (this is tested).
*Trade-off:* one region is bounded by one machine. With about 50 k units nationally and 1–5 k per region, that is far from a limit.

### 6.2 Two-stage candidate selection
A **spatial grid** keeps the free units, with O(1) updates as GPS pings arrive. It returns the k nearest by straight line, and then **contraction hierarchies** compute exact road ETAs for those k (see `DispatchEngine.dispatch`).
*Why not a road ETA to every unit?* That is O(fleet) queries per call. *Why not straight line alone?* In the simulator it costs 3.6 pp of critical calls reached in time (paired CI 2.9–4.4). Mountains, rivers and one-way systems make the nearest unit by straight line a slow one.

### 6.3 Contraction hierarchies
Preprocessing contracts nodes in order of importance (lazy updates keyed on edge difference, deleted neighbours and level). It adds a shortcut only when a bounded witness search finds no alternative path. A query then runs a bidirectional search that only moves upward, settling about 28 nodes instead of about 7,900. Shortcuts record the edges they replace, so routes unpack exactly. Correctness is tested against Dijkstra on random graphs and 300 real-network queries.

### 6.4 Hospitals: precompute instead of query
At startup, one **reverse Dijkstra** per hospital computes the travel time from every node to that hospital. Choosing a hospital then costs H array lookups. Specialty constraints are handled with "best capable, otherwise nearest".

### 6.5 Batch dispatch with the Hungarian algorithm
During a surge, assigning calls one at a time can be badly sub-optimal. `dispatchBatch` solves the assignment problem globally, using cost = severity weight × ETA, in O(n³). The tests include a case where the greedy order costs 3× more.

### 6.6 Base placement with MEXCLP
Choosing where idle units wait is the Maximum Expected Covering Location Problem. Each unit is busy with probability q, so a zone covered by k units is covered with probability 1 − qᵏ. The objective is monotone submodular, so **lazy greedy** carries a (1 − 1/e) guarantee. On small instances it is within 10% of the brute-force optimum, and this is tested.

### 6.7 ML is advisory, never blocking
Triage calls the Python sidecar with a 400 ms timeout inside a **circuit breaker** (CLOSED → OPEN after 3 failures → HALF_OPEN probe after 10 s). On any failure the call falls back to deterministic rules, and every result records which path produced it. The LLM answers through an enum-only tool schema, so free text can never reach the dispatcher.

### 6.8 Durability
Incidents are appended to a JSON-lines **write-ahead log** before the API acknowledges them, with fsync configurable. Replay ignores a torn final line (tested). In production the WAL would be a replicated log, such as Kafka with acks=all or Raft, and a standby engine would tail it for fail-over.

## 7. Failure modes

| Failure | Effect | Mitigation |
|---|---|---|
| ML sidecar down or slow | Triage quality drops | Circuit breaker, then rules. Dispatch unaffected (tested) |
| Engine crash | Region unavailable | Standby replays the WAL. Clients retry with the same idempotency key |
| Duplicate POST (network retry) | Two ambulances for one patient | Idempotency-key cache returns the original response (tested) |
| Client flood or bug | Overload | Per-client token bucket → 429 with Retry-After (tested) |
| GPS gap | Stale unit position | Last known node. Dispatch ETA recomputed on update |
| Unit cannot reach the incident | Wrong assignment | Graph limited to the largest SCC. Unreachable routes are skipped |
| All units busy | Waiting calls | Severity-then-age queue. A freed unit serves the queue before returning to base |
| Road closure | Wrong ETAs | Edge weights are data. CH rebuild takes 0.07 s on this graph, so rebuild often, or switch to CCH for live weights (see §9) |

## 8. Observability
Prometheus `/metrics`: request counts by status, dispatch latency histogram, route latency per algorithm, incidents by priority, queued incidents, rate-limited requests, route-cache hit ratio. The dashboard shows live KPIs: P1 ≤ 8 min, median response, units busy, queue length.

## 9. What I would build next
- **Customizable CH** (separate metric-independent contraction from per-metric customisation), so live traffic and closures update in seconds.
- **Real-time traffic speeds** and time-of-day edge weights.
- **Live hospital bed and diversion feeds**, so a full ED is avoided.
- **Cross-region hand-off** when the best unit sits across a region border: two-phase reservation between region writers.
- **Off-policy evaluation** on real dispatch logs before any policy goes live, then a staged rollout in shadow mode.
