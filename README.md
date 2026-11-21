# 🚑 GoldenHour: real-time ambulance dispatch and pre-positioning

**On the real road network of central Chennai (21,336 junctions, OpenStreetMap), GoldenHour reaches 79% of life-threatening calls within 8 minutes with 20 ambulances. Crews waiting at hospitals and dispatched by straight-line distance reach 31%.** It does this with three things: dispatch based on actual road travel time computed with contraction hierarchies, an optimiser that decides where idle crews wait, and a demand forecast trained on real 911 calls. The same code runs on a second city (Andorra's mountain roads) to show what transfers and what doesn't.

> For cardiac arrest, survival falls by roughly 7–10% for every minute without CPR or defibrillation. Major trauma has a "golden hour". Most ambulance response time is not spent driving. It is lost by sending the wrong unit, or by having crews waiting in the wrong place.

**Live demo:** [huggingface.co/spaces/Shubham2019/goldenhour](https://huggingface.co/spaces/Shubham2019/goldenhour) (runs entirely in your browser) · **Design doc:** [docs/DESIGN.md](docs/DESIGN.md) · **Model card:** [docs/MODEL_CARD.md](docs/MODEL_CARD.md)

| | |
|---|---|
| **DSA** | Contraction hierarchies (43× faster than Dijkstra on Chennai, exact). A*, bidirectional Dijkstra, k-d tree, moving-object spatial grid, indexed heap with decrease-key, Hungarian algorithm, lazy-greedy submodular optimisation, iterative Tarjan SCC, LRU, token bucket, consistent hashing. All hand-written in Java with no dependencies, and each is tested against a brute-force oracle. |
| **System design** | Single-writer dispatch shards routed by consistent hashing. Write-ahead log, idempotency keys, a circuit breaker around the ML call, rate limiting, Prometheus metrics. Detailed in [DESIGN.md](docs/DESIGN.md): capacity estimates, failure modes, trade-offs. |
| **ML / AI** | LightGBM Poisson demand forecasting on **48,812 real EMS calls**, evaluated honestly against baselines on a 4-week holdout. A statistical test of whether the spatial pattern of calls changes by shift. LLM triage via tool-calling with a strict schema, falling back to deterministic rules. |
| **Evidence** | A discrete-event simulator compares policies on identical call streams (common random numbers), reporting paired 95% confidence intervals. 25 Java tests and 7 Python tests run in CI. |

---

## Results

### 1. Same fleet, better decisions (simulation, 10 seeds × 12 weeks per arm)

**Chennai** (main demo). 20 ambulances, 162 demand zones, 12 major hospitals with stroke, cardiac, trauma, burns and maternity capabilities, and road speeds reduced 40% for traffic. [Full table](reports/simulation.md).

| Configuration | P1 median response | **P1 reached ≤ 8 min** | Δ vs previous step (95% CI) |
|---|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 10.2 min | **30.8%** | — |
| + coverage-optimised bases (MEXCLP, uniform demand) | 6.6 min | **68.5%** | +37.7 pp (+36.8 … +38.6) |
| + dispatch by real road ETA (contraction hierarchies) | 6.1 min | **78.6%** | **+10.1 pp** (+9.8 … +10.5) |
| + bases weighted by the demand forecast | 6.3 min | 78.1% | −0.5 pp (not significant) |
| _alt:_ re-optimise bases every 8 h shift | 6.3 min | 77.3% | −0.9 pp (not significant) |
| _alt:_ dynamic move-up of idle units | 6.2 min | 78.5% | +0.3 pp (not significant) |

**Fleet sizing:** 13 optimised ambulances (53.9%) outperform 25 under the status quo (32.3%); even 10 optimised ambulances (34.7%) do better than those 25.

**Andorra** (mountain roads, 8 ambulances). [Full table](reports/simulation_andorra.md).

| Configuration | **P1 reached ≤ 8 min** | Δ vs previous step (95% CI) |
|---|---:|---:|
| Status quo | **32.0%** | — |
| + coverage-optimised bases | **71.3%** | +39.2 pp (+37.8 … +40.7) |
| + road-network ETA dispatch | **74.9%** | +3.6 pp (+2.9 … +4.4) |
| + forecast-weighted bases | **78.1%** | **+3.2 pp** (+2.1 … +4.2) |
| _alt:_ re-optimise every shift | 76.2% | −1.9 pp (−2.9 … −0.8) |

**What the two cities teach:**
- **Road-ETA dispatch matters almost three times more in Chennai** (+10.1 pp against +3.6 pp). A dense grid of one-way streets, flyovers and the Adyar and Cooum rivers makes "nearest by straight line" wrong far more often than open valley roads do.
- **The demand forecast only pays off where demand is concentrated.** Andorra's people live in a few valley towns, so forecast weighting adds +3.2 pp. Central Chennai is uniformly dense (no zone above 1.3% of calls), so a uniform placement is already near-optimal, and the forecast adds nothing measurable. Knowing *when* an ML component is worth deploying is part of the design.
- **Re-positioning every shift never helped**, in either city. The forecasting analysis below explains why.

### 2. Demand forecasting on real 911 data (Montgomery County, PA; holdout = last 4 weeks)

| Model | Poisson deviance ↓ | WAPE per zone-shift ↓ | Hotspot capture@5 ↑ |
|---|---:|---:|---:|
| Seasonal naive (same hour last week) | 1.116 | 99.3% | 19.5% |
| Historical average (zone × hour-of-week) | 0.472 | 80.2% | 22.9% |
| Separable (zone rate × hour-of-week profile) | **0.401** | 79.7% | 22.7% |
| LightGBM, Poisson objective, 14 lag/calendar features | **0.401** | 79.7% | 22.8% |

**Finding:** gradient boosting does not beat a separable model. At the level of one zone in one hour, calls are close to pure Poisson noise: the oracle that already knows the top 5 zones only captures 32%. So the planner uses the separable model, which is simpler, easy to explain, and the reason the model *transfers to a new city*. A spatial-stability test (total variation distance by shift compared with bootstrap noise) shows that only about 0.4–1.5% of call volume moves between shifts. That matches the negative simulation result above.

### 3. Performance on the real road graph (2,000 random queries, after warm-up)

| Router | Chennai p50 | Chennai p99 | Nodes settled | Andorra p50 |
|---|---:|---:|---:|---:|
| Dijkstra | 1,126 µs | 2,240 µs | 10,837 | 480 µs |
| A* (haversine / max-speed heuristic) | 1,140 µs | 3,629 µs | 7,285 | 620 µs |
| Bidirectional Dijkstra | 674 µs | 1,911 µs | 5,911 | 368 µs |
| **Contraction hierarchies** | **26 µs** | **76 µs** | **133** | **14 µs** |

Preprocessing the hierarchy takes **0.7 s** for Chennai. A full dispatch decision (snap the location, filter to the k nearest units, compute 8 exact road ETAs, apply the ALS rule, choose a hospital) takes **p50 108 µs and p99 386 µs**, about 6,500 decisions per second on one core. A* settles fewer nodes but is no faster. Its heuristic has to stay admissible for the fastest road, which makes it weak on slow city streets, and computing it costs time.

**A bug the real data found:** OpenStreetMap's Chennai extract contains self-loop edges. Contracting a node with a self-loop added a shortcut to the very list being iterated, so preprocessing never finished. The fix is a one-line guard, and a regression test now covers it.

### 4. Triage

The rule-based triage misses half the P1 calls in a labelled set of 38 realistic call notes: P1 recall is 0.50 and the under-triage rate is 29%. For example, "my dad is grey and sweaty and his jaw hurts" contains no keyword the rules recognise. That gap is why the LLM path exists. It uses tool-calling with an enum-only schema, a 400 ms budget and a circuit breaker. Set `ANTHROPIC_API_KEY` and run `python ml/triage/evaluate.py` to compare the two.

---

## Architecture

```mermaid
flowchart LR
  caller([Caller / call-taker]) -->|POST /incidents<br/>Idempotency-Key| api
  subgraph core[Java dispatch core — single writer per region]
    api[HTTP API<br/>rate limit · validation] --> wal[(Write-ahead log)]
    api --> triage{Triage}
    triage -->|400 ms, circuit breaker| ml
    triage -.->|fallback| rules[Keyword rules]
    api --> engine[Dispatch engine]
    engine --> grid[Spatial grid<br/>k nearest free units]
    engine --> ch[Contraction hierarchies<br/>exact road ETA]
    engine --> hosp[Reverse Dijkstra trees<br/>hospital by specialty]
    engine --> queue[Severity queue]
    planner[Coverage planner<br/>MEXCLP lazy greedy] --> engine
  end
  ml[Python ML sidecar<br/>FastAPI · LLM triage] 
  forecast[Forecast pipeline<br/>LightGBM / separable Poisson] -->|demand per zone × hour| planner
  osm[(OpenStreetMap)] --> graph[Graph builder<br/>largest SCC → CSR] --> ch
  data911[(911 calls)] --> forecast
  engine --> dash[Live dashboard<br/>Leaflet]
```

**Request path:** validate and rate-limit, write to the WAL, triage, then grid filter (k nearest free units), then exact CH ETAs for those k units, then the ALS rule, then choose a hospital by specialty using precomputed reverse trees, then assign or queue. Everything except the optional ML call is in-memory and lock-free across regions. [DESIGN.md](docs/DESIGN.md) covers scaling to a country: sharding regions on a consistent-hash ring, replicated WALs, hand-off of incidents across region borders, and the 10× load surge.

## Try it

```bash
# Java 21 only: builds, runs 25 tests, packages the jar (no Maven download)
core/build.sh
java -jar core/out/goldenhour.jar serve            # → http://localhost:8080 (Chennai; GH_DATA=data/city for Andorra)
java -jar core/out/goldenhour.jar simulate data/chennai 20 0.35   # regenerate reports/simulation.md
java -jar core/out/goldenhour.jar bench data/chennai              # regenerate reports/benchmarks.md

# or everything, including the ML sidecar
docker compose up --build                           # → http://localhost:8080

# reproduce every number from the raw public data
scripts/reproduce.sh
```

**No server? No problem.** `scripts/build_static.sh` exports the road graph and the contraction hierarchy (preprocessed by the Java core) into `web/data/`. The same dashboard then runs entirely in the browser (`web/engine.js` ports the CH query, dispatch and live fleet logic). That is how the free static demo is hosted. In the browser, all four routers return identical answers on 200 random queries.

**Any city in the world.** Besides the prebuilt cities, the city picker's **🌍 Any city in the world** option (or "Build it around me") builds a dispatch system for any place on Earth, live in the browser, in about 10–40 seconds:
1. It downloads the drivable roads and hospitals within 4 km from OpenStreetMap, using the Overpass API.
2. It builds the road graph, keeping only the largest strongly connected component (iterative Kosaraju) so every point can reach every other.
3. It estimates demand zones from residential street length, with the hour-of-week profile learned from the real 911 data.
4. It picks hospitals and infers what each treats from its name (an assumption). Phone numbers come from OpenStreetMap when tagged.
5. It places ambulances with the same lazy-greedy MEXCLP coverage objective as the Java planner, on 7-minute drive-time coverage.

The emergency number shown follows the country. The built city is cached in IndexedDB, so a refresh doesn't download it again. Cities built live route with A*. Contraction-hierarchy preprocessing is done offline in Java for the prebuilt cities (`web/anywhere.js`).

**Dashboard:** click the map and describe the emergency. You see the triage result, every candidate unit with its straight-line distance next to its road ETA, and the chosen route. Ambulances then drive their routes live on the map. The clock starts at the real local time and runs in real time by default; switch to 10× or 60× to watch a whole call play out. **📍 Use my location** reports the emergency at your own position (when you are inside the mapped area). The **Routing lab** tab runs one query through all four algorithms side by side. The **Results** tab shows every table above.

**API**
```http
POST /api/v1/incidents     {"lat":42.507,"lon":1.521,"notes":"not breathing"}   → 201 dispatched | 202 queued | 429
GET  /api/v1/route?from=42.50,1.52&to=42.56,1.65&algo=ch|dijkstra|astar|bidi
GET  /api/v1/state   GET /api/v1/demand   GET /api/v1/reports   GET /metrics   GET /health
```

## Run it on your own city

```bash
python ml/data_prep/build_road_graph.py --point 18.52,73.86 --dist 6000 --speed-factor 0.6 --out data/pune   # needs osmnx
(cd ml/forecast && python transfer.py --city ../../data/pune --population 3100000)
GH_DATA=data/pune java -jar core/out/goldenhour.jar serve
```

## Data, honestly stated

- **Roads:** real OpenStreetMap networks: central Chennai (a 6 km radius around T. Nagar, built with `build_road_graph.py --point 13.05,80.24 --dist 6000 --speed-factor 0.6`) and Andorra. I keep the largest strongly connected component, so every node can reach every other node. Speeds are scaled by 0.6 in Chennai for traffic, which is an assumption.
- **Chennai hospitals and demand:** 12 major hospitals with capabilities approximated from public information (verify before any real use). The population of about 1.9 M is an estimate for the area. Per-capita EMS demand is set to 0.35× the US county rate, because Indian per-capita EMS use is lower. That is an assumption, and it is the `volume` parameter.
- **Service-area guard:** a patient location or hospital more than 300 m from any mapped road is rejected rather than silently snapped to a road that could be across a river. That guard is why MIOT, which sits on the far bank of the Adyar at the edge of the extract, is excluded. The dashboard draws the covered area as a dashed outline (convex hull of the road network).
- **Calls:** real anonymised 911 calls from Montgomery County, PA ([Kaggle](https://www.kaggle.com/datasets/mchirico/montcoalert)). Those calls happened in Pennsylvania, not in Chennai or Andorra. So the forecasting is *validated* on the real data. What is *transferred* to the demo city is only what generalises: the hour-of-week profile, EMS calls per resident, and the case mix. The spatial distribution of demand in the demo city comes from its own residential street density.
- **Hospital capabilities** are not tagged in OSM. The hospital closest to the city's demand centroid is treated as the full-service regional hospital. Response-time results are from simulation, not from a field trial.

## Repository map

```
core/      Java 21, zero dependencies: graph, routing, ds, dispatch, sim, infra, api, tools (25 tests)
ml/        data_prep (OSM → graph) · forecast (911 → model → demand) · triage (rules + LLM) · service (FastAPI)
web/       Leaflet dashboard; engine.js = in-browser edition (serverless demo)
reports/   generated results: simulation.md, benchmarks.md, forecast_metrics.json, triage_eval.json
docs/      DESIGN.md · MODEL_CARD.md · INTERVIEW.md
```

MIT licensed.
