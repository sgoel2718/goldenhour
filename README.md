# 🚑 GoldenHour: real-time ambulance dispatch and pre-positioning

**In a simulation on a real road network, with the same 8 ambulances, GoldenHour reaches 78% of life-threatening calls within 8 minutes. The common status quo reaches 32%.** It does this with three things: a demand forecast trained on real 911 calls, an optimiser that decides where idle crews wait, and dispatch based on actual road travel time computed with contraction hierarchies.

> For cardiac arrest, survival falls by roughly 7–10% for every minute without CPR or defibrillation. Major trauma has a "golden hour". Most ambulance response time is not spent driving. It is lost by sending the wrong unit, or by having crews waiting in the wrong place.

**Live demo:** [huggingface.co/spaces/Shubham2019/goldenhour](https://huggingface.co/spaces/Shubham2019/goldenhour) (runs entirely in your browser) · **Design doc:** [docs/DESIGN.md](docs/DESIGN.md) · **Model card:** [docs/MODEL_CARD.md](docs/MODEL_CARD.md)

| | |
|---|---|
| **DSA** | Contraction hierarchies (33× faster than Dijkstra, exact). A*, bidirectional Dijkstra, k-d tree, moving-object spatial grid, indexed heap with decrease-key, Hungarian algorithm, lazy-greedy submodular optimisation, iterative Tarjan SCC, LRU, token bucket, consistent hashing. All hand-written in Java with no dependencies, and each is tested against a brute-force oracle. |
| **System design** | Single-writer dispatch shards routed by consistent hashing. Write-ahead log, idempotency keys, a circuit breaker around the ML call, rate limiting, Prometheus metrics. Detailed in [DESIGN.md](docs/DESIGN.md): capacity estimates, failure modes, trade-offs. |
| **ML / AI** | LightGBM Poisson demand forecasting on **48,812 real EMS calls**, evaluated honestly against baselines on a 4-week holdout. A statistical test of whether the spatial pattern of calls changes by shift. LLM triage via tool-calling with a strict schema, falling back to deterministic rules. |
| **Evidence** | A discrete-event simulator compares policies on identical call streams (common random numbers), reporting paired 95% confidence intervals. 24 Java tests and 7 Python tests run in CI. |

---

## Results

### 1. Same fleet, better decisions (simulation, 10 seeds × 12 weeks per arm)

| Configuration | P1 median response | **P1 reached ≤ 8 min** | Δ vs previous step (95% CI) |
|---|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 11.6 min | **32.0%** | — |
| + coverage-optimised bases (MEXCLP, uniform demand) | 5.6 min | **71.3%** | +39.2 pp (+37.8 … +40.7) |
| + dispatch by real road ETA (contraction hierarchies) | 5.6 min | **74.9%** | +3.6 pp (+2.9 … +4.4) |
| + bases weighted by the demand forecast | 5.2 min | **78.1%** | +3.2 pp (+2.1 … +4.2) |
| _alt:_ re-optimise bases every 8 h shift | 5.5 min | 76.2% | **−1.9 pp** (−2.9 … −0.8) |
| _alt:_ dynamic move-up of idle units | 5.5 min | 78.1% | +0.1 pp (not significant) |

**Fleet sizing:** the optimised system with **5** ambulances (56.9%) outperforms the status quo with **10** (36.1%). Full tables are in [reports/simulation.md](reports/simulation.md).

**Two negative results that shaped the design.** Re-positioning crews every shift made outcomes *worse*, and continuous move-up gave no measurable gain. The forecasting work below explains why: *where* emergencies happen barely changes during the day, so moving crews around only costs time spent driving. I kept the simpler policy.

### 2. Demand forecasting on real 911 data (Montgomery County, PA; holdout = last 4 weeks)

| Model | Poisson deviance ↓ | WAPE per zone-shift ↓ | Hotspot capture@5 ↑ |
|---|---:|---:|---:|
| Seasonal naive (same hour last week) | 1.116 | 99.3% | 19.5% |
| Historical average (zone × hour-of-week) | 0.472 | 80.2% | 22.9% |
| Separable (zone rate × hour-of-week profile) | **0.401** | 79.7% | 22.7% |
| LightGBM, Poisson objective, 14 lag/calendar features | **0.401** | 79.7% | 22.8% |

**Finding:** gradient boosting does not beat a separable model. At the level of one zone in one hour, calls are close to pure Poisson noise: the oracle that already knows the top 5 zones only captures 32%. So the planner uses the separable model, which is simpler, easy to explain, and the reason the model *transfers to a new city*. A spatial-stability test (total variation distance by shift compared with bootstrap noise) shows that only about 0.4–1.5% of call volume moves between shifts. That matches the negative simulation result above.

### 3. Performance on the real road graph (16,411 nodes, 2,000 random queries)

| Router | p50 | p99 | Nodes settled |
|---|---:|---:|---:|
| Dijkstra | 480 µs | 959 µs | 7,942 |
| A* (haversine / max-speed heuristic) | 620 µs | 1,989 µs | 5,517 |
| Bidirectional Dijkstra | 368 µs | 813 µs | 5,194 |
| **Contraction hierarchies** | **14 µs** | **57 µs** | **28** |

A full dispatch decision (snap the location, filter to the k nearest units, compute 8 exact road ETAs, apply the ALS rule, choose a hospital) takes **p50 67 µs and p99 377 µs**, about 7,000 decisions per second on one core. A* is *slower* than plain Dijkstra here, even though it settles fewer nodes. The heuristic has to be admissible for 90 km/h motorways, which makes it too weak on slow mountain roads, and the cost of computing it outweighs the savings.

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
# Java 21 only: builds, runs 24 tests, packages the jar (no Maven download)
core/build.sh
java -jar core/out/goldenhour.jar serve            # → http://localhost:8080
java -jar core/out/goldenhour.jar simulate          # regenerate reports/simulation.md
java -jar core/out/goldenhour.jar bench             # regenerate reports/benchmarks.md

# or everything, including the ML sidecar
docker compose up --build                           # → http://localhost:8080

# reproduce every number from the raw public data
scripts/reproduce.sh
```

**No server? No problem.** `scripts/build_static.sh` exports the road graph and the contraction hierarchy (preprocessed by the Java core) into `web/data/`. The same dashboard then runs entirely in the browser (`web/engine.js` ports the CH query, dispatch and live fleet logic). That is how the free static demo is hosted. In the browser, all four routers return identical answers on 200 random queries.

**Dashboard:** click the map and describe the emergency. You see the triage result, every candidate unit with its straight-line distance next to its road ETA, and the chosen route. Ambulances then drive their routes live on the map at 60× speed. The **Routing lab** tab runs one query through all four algorithms side by side. The **Results** tab shows every table above.

**API**
```http
POST /api/v1/incidents     {"lat":42.507,"lon":1.521,"notes":"not breathing"}   → 201 dispatched | 202 queued | 429
GET  /api/v1/route?from=42.50,1.52&to=42.56,1.65&algo=ch|dijkstra|astar|bidi
GET  /api/v1/state   GET /api/v1/demand   GET /api/v1/reports   GET /metrics   GET /health
```

## Run it on your own city

```bash
python ml/data_prep/build_road_graph.py --place "Pune, India" --out data/pune     # needs osmnx
(cd ml/forecast && python transfer.py --city ../../data/pune --population 3100000)
GH_DATA=data/pune java -jar core/out/goldenhour.jar serve
```

## Data, honestly stated

- **Roads:** the real OpenStreetMap network of Andorra, the extract that GraphHopper uses for its own tests. I keep the largest strongly connected component, so every node can reach every other node.
- **Calls:** real anonymised 911 calls from Montgomery County, PA ([Kaggle](https://www.kaggle.com/datasets/mchirico/montcoalert)). Those calls happened in Pennsylvania, not Andorra. So the forecasting is *validated* on the real data. What is *transferred* to the demo city is only what generalises: the hour-of-week profile, EMS calls per resident, and the case mix. The spatial distribution of demand in the demo city comes from its own residential street density.
- **Hospital capabilities** are not tagged in OSM. The hospital closest to the city's demand centroid is treated as the full-service regional hospital. Response-time results are from simulation, not from a field trial.

## Repository map

```
core/      Java 21, zero dependencies: graph, routing, ds, dispatch, sim, infra, api (24 tests)
ml/        data_prep (OSM → graph) · forecast (911 → model → demand) · triage (rules + LLM) · service (FastAPI)
web/       Leaflet dashboard; engine.js = in-browser edition (serverless demo)
reports/   generated results: simulation.md, benchmarks.md, forecast_metrics.json, triage_eval.json
docs/      DESIGN.md · MODEL_CARD.md · INTERVIEW.md
```

MIT licensed.
