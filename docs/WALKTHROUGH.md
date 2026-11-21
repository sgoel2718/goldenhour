# GoldenHour, from zero: a study guide

This guide is for learning the project well enough to explain any line of it in an interview. Read it in order. Each part has the idea first, then the real code with notes on what each line does, then what to say if someone asks about it.

---

## Part 0. The problem in one paragraph

When someone calls 108, three decisions decide how fast help arrives:

1. **Which ambulance goes?** Most systems pick the closest one in a straight line, but roads, one-way streets and rivers make the closest unit by distance often not the fastest by road.
2. **Which hospital?** It should be the nearest one that can treat the case. A stroke needs a stroke unit; a sprained ankle needs any hospital.
3. **Where do idle ambulances wait?** Usually at hospitals. Better: at spots chosen so most of the city is within 8 minutes.

GoldenHour makes all three decisions with algorithms. It then proves the gain in a simulator: on Chennai's real roads, the share of critical calls reached within 8 minutes went from **31% to 79% with the same 20 ambulances**.

---

## Part 1. How to read the project (the roadmap)

Read in this order. Each step uses what came before it.

| Step | Read | What you learn |
|---|---|---|
| 1 | `README.md` | What it does, headline results |
| 2 | `docs/DESIGN.md` | Architecture and why each choice |
| 3 | `ml/data_prep/build_road_graph.py` | How OpenStreetMap roads become `nodes.csv` / `edges.csv` |
| 4 | `core/.../graph/RoadGraph.java`, `Geo.java` | How a city is stored in memory (CSR) |
| 5 | `core/.../ds/IndexedMinHeap.java` | The heap every shortest-path search uses |
| 6 | `core/.../routing/Dijkstra.java`, `AStar.java`, `BidirectionalDijkstra.java` | Shortest paths, three ways |
| 7 | `core/.../routing/ContractionHierarchy.java` | The fast router (the hardest file) |
| 8 | `core/.../ds/KdTree.java`, `SpatialGrid.java`, `LruCache.java` | Finding nearby things fast |
| 9 | `core/.../dispatch/DispatchEngine.java` | The dispatch decision itself |
| 10 | `core/.../dispatch/Hungarian.java`, `CoverageOptimizer.java` | Surge matching and ambulance placement |
| 11 | `core/.../sim/Scenario.java`, `Simulator.java`, `Experiments.java` | How the results are measured |
| 12 | `core/.../infra/*`, `ds/TokenBucket.java`, `ds/ConsistentHashRing.java`, `api/Server.java` | Production concerns: durability, failure, rate limits, sharding |
| 13 | `ml/forecast/pipeline.py`, `spatial_stability.py`, `transfer.py` | Demand forecasting |
| 14 | `ml/triage/rules.py`, `llm.py`, `core/.../triage/Triage.java` | Reading call notes into a severity |
| 15 | `web/engine.js`, `web/anywhere.js`, `web/index.html` | The browser edition (live demo) |
| 16 | `core/.../tools/ExportStatic.java`, `scripts/build_city_bundle.py` | How a city becomes a browser bundle |

The Java root is `core/src/main/java/com/goldenhour/`. Tests are in `core/src/test/java/com/goldenhour/`.

### How to run each piece

```bash
cd core && ./build.sh                 # compiles, runs 26 tests, builds out/goldenhour.jar
java -jar out/goldenhour.jar bench ../data/chennai       # routing benchmark
java -jar out/goldenhour.jar simulate ../data/chennai    # experiments -> reports/simulation.md
java -jar out/goldenhour.jar serve ../data/chennai       # REST API + dashboard on :8080
cd ../ml && python -m pytest tests                       # 7 Python tests
python forecast/pipeline.py                              # trains the forecast, writes reports/forecast_metrics.json
```

The best way to learn: put a breakpoint (or a `System.out.println`) in `DispatchEngine.dispatch`, run `serve`, send one call from the dashboard, and step through.

---

## Part 2. Every concept used, in one list

**Data structures (DSA)**
- CSR (compressed sparse row) graph, built with counting sort
- Indexed binary min-heap with decrease-key and O(touched) reset
- k-d tree (static nearest neighbour), built with quickselect
- Uniform spatial grid with ring search (moving points)
- LRU cache: hash map plus doubly linked list
- Token bucket (rate limiting)
- Consistent hash ring with virtual nodes (TreeMap)
- Priority queue as an event queue (discrete-event simulation)

**Algorithms**
- Dijkstra, one-to-one and one-to-many with a budget, forward and reverse
- A* with an admissible heuristic (straight-line distance ÷ max speed)
- Bidirectional Dijkstra and its stopping rule
- Contraction Hierarchies: node ordering, witness search, shortcuts, upward search, unpacking
- Kosaraju strongly connected components (browser: keep only the reachable part of the road network)
- Hungarian algorithm (optimal assignment)
- Lazy greedy for a submodular objective (MEXCLP), with the 1 − 1/e guarantee
- Haversine distance, equirectangular projection, convex hull

**System design**
- Single-writer per region; regions sharded on a consistent-hash ring
- Write-ahead log before acknowledging; replay on restart
- Idempotency keys (safe client retries)
- Rate limiting with HTTP 429
- Circuit breaker with fallback (graceful degradation)
- Metrics (counters and latency histograms), health checks
- Caching hot routes
- Offline preprocessing, online fast queries (the CH pattern)

**ML / statistics**
- Poisson regression with LightGBM (counts, not regression on raw values)
- Lag and rolling features; time-based train/validation/test split; early stopping
- Baselines: seasonal naive, historical average, separable model
- Metrics: Poisson deviance, MAE, WAPE, hotspot capture@k against an oracle
- Spatial stability test: total variation distance against a bootstrap
- Transfer learning by structure (hour-of-week profile, per-capita rate, case mix)
- Empirical-Bayes shrinkage for sparse per-shift counts
- Simulation methodology: common random numbers, paired 95% confidence intervals
- LLM tool-calling with enum-only schemas; deterministic rules fallback

**Web**
- Leaflet maps; Overpass (OSM data), OSRM (routing), Nominatim and Photon (search)
- gzip + base64 bundles inflated with `DecompressionStream`
- localStorage (calls survive refresh), IndexedDB (city cache)
- BroadcastChannel (dispatcher tab → crew tab), Web Audio (alarm)

---

## Part 3. The road network

### 3.1 Where the data comes from

`ml/data_prep/build_road_graph.py` downloads a city from OpenStreetMap with OSMnx:

- Each **junction** becomes a node (id, lat, lon).
- Each **road segment** becomes a directed edge (from, to, length in metres, speed in km/h). One-way streets give one edge; two-way streets give two.
- Speeds come from the road type (motorway fast, residential slow) times `--speed-factor` for congestion.
- **Self-loops** (a road that starts and ends at the same junction) are dropped. This matters: they caused the CH hang described in 3.5.

Output: `data/<city>/nodes.csv`, `edges.csv`, plus `facilities.csv` (hospitals found in OSM).

### 3.2 CSR: storing a graph in arrays (`RoadGraph.java`)

**Idea.** A list of objects per node (`List<Edge>[]`) wastes memory and is slow (pointer chasing). CSR keeps everything in flat arrays:

```
outStart = [0, 2, 3, 3, 5]     // node v's edges are outStart[v] .. outStart[v+1]-1
outTarget = [1, 2, 3, 0, 1]    // edge -> target node
outWeight = [...]              // edge -> travel seconds
```

Node 0's edges are indices 0 and 1. Node 2 has none (3..3 is empty).

**How it is built: counting sort.**

1. Count edges per source: `outStart[from + 1]++` for each edge.
2. Prefix sum: `outStart[i+1] += outStart[i]`. Now `outStart[v]` is where v's block starts.
3. Copy `outStart` into a write pointer `pos`, then place each edge at `pos[from]++`.

O(n + m) time, no sorting comparisons. The same file builds the reverse (incoming) CSR, which reverse Dijkstra uses ("how long from every node *to* this hospital").

`edgeWeight = length / (speed / 3.6)` turns metres and km/h into seconds. `maxSpeed` is kept for A*.

**Interview line:** "CSR is cache-friendly: scanning a node's neighbours reads one contiguous slice of an array."

### 3.3 The indexed min-heap (`ds/IndexedMinHeap.java`)

**Why not `java.util.PriorityQueue`?** Dijkstra keeps lowering a node's distance. `PriorityQueue` cannot lower a key, so you add duplicates and skip stale ones. That works, but uses more memory and time. An indexed heap supports **decrease-key**: it knows where each node sits in the heap.

```java
private final int[] heap, pos;   // heap[i] = node at slot i;  pos[node] = its slot
private final double[] key;      // key[node] = current priority
private final int[] touched;     // nodes seen since the last reset
```

`pos[id]` has three meanings:
- `>= 0`: the node is in the heap at that slot
- `-1`: never seen since the last reset
- `-2`: already popped (settled)

```java
public void pushOrDecrease(int id, double k) {
    if (pos[id] >= 0) {                                   // already in heap
        if (k < key[id]) { key[id] = k; siftUp(pos[id]); } // smaller key: move it up
        return;
    }
    if (pos[id] == -1) touched[touchedCount++] = id;      // remember for reset()
    key[id] = k;
    heap[size] = id;                                      // put at the end
    pos[id] = size;
    siftUp(size++);                                       // bubble up to its place
}
```

```java
public int pop() {
    int top = heap[0];                 // smallest key
    int last = heap[--size];           // take the last element
    pos[top] = -2;                     // mark popped
    if (size > 0) { heap[0] = last; pos[last] = 0; siftDown(0); }  // move last to the root, sink it
    return top;
}
```

```java
public void reset() {                  // O(touched), not O(n)
    for (int i = 0; i < touchedCount; i++) pos[touched[i]] = -1;
    touchedCount = 0; size = 0;
}
```

**The reset trick is the important part.** Chennai has 21k nodes, but a CH query touches about 130. Clearing 21k entries per query would cost more than the query itself. So we only clear what we touched. The routers use the same trick for their distance arrays.

`siftUp`: while the parent's key is bigger, move the parent down one slot. Parent of slot i is `(i - 1) / 2`. `siftDown`: pick the smaller child (`2i+1`, `2i+2`); if it's smaller than us, move it up. Both update `pos[]` for every element they move.

### 3.4 Dijkstra, A*, bidirectional (`routing/`)

**Dijkstra.** Always expand the closest unsettled node. When a node is popped, its distance is final (weights are non-negative).

```
dist[s] = 0; heap.push(s, 0)
while heap not empty:
    u = heap.pop()
    if u == t: stop                  // one-to-one: done as soon as t is popped
    for each edge u -> x with weight w:
        if dist[u] + w < dist[x]:
            dist[x] = dist[u] + w
            parent[x] = u
            heap.pushOrDecrease(x, dist[x])
```

Complexity: O((n + m) log n).

`Dijkstra.java` also has:
- `distancesFrom(s, budget)`: one-to-many, stops when the popped distance passes the budget. The simulator uses this for "which zones can this base reach within 7 minutes".
- `distancesTo(t, budget)`: the same on the reverse graph. `DispatchEngine.addHospital` calls it once per hospital, so later "time from any node to hospital h" is an array lookup: O(1).

**A\*.** Same loop, but the heap key is `dist[u] + h(u)`, where `h(u)` = straight-line distance to t ÷ the fastest speed in the graph. That never overestimates the true time (it is *admissible*), so A* stays exact. It points the search toward the target.

*Finding to quote:* on Chennai A* settles about a third fewer nodes but is no faster. The heuristic is weak (it assumes motorway speed on 15 km/h streets) and computing it costs time.

**Bidirectional Dijkstra.** Search forward from s and backward from t at the same time. Keep `best = min over nodes v of df[v] + db[v]`. Stop when `topF + topB >= best`: no unexplored path can be shorter. It settles roughly half the nodes of one-way Dijkstra.

### 3.5 Contraction Hierarchies (`routing/ContractionHierarchy.java`)

This is the file that impresses interviewers. Take it slowly.

**The idea in plain words.**

1. **Preprocessing (once, about 0.7 s for Chennai).** Rank all nodes from least to most important, roughly: dead ends and side streets first, highway junctions last. Remove ("contract") nodes in that order. When you remove v, for each pair u → v → x where the shortest u-to-x path really goes through v, add a **shortcut** edge u → x with weight w(u,v) + w(v,x). That keeps all distances correct among the nodes still left.
2. **Query (26 µs).** Every shortest path can be written as "go up in rank, then go down". So run bidirectional Dijkstra where the forward search only uses edges going **up** in rank, and the backward search only uses edges that go up in rank when walked backward. Both searches climb to the important roads quickly and meet. About 130 nodes are settled instead of about 10,800.
3. **Unpacking.** Each shortcut remembers its two child edges. Expanding them recursively gives the real street-by-street path.

**Storage.** All edges (original + shortcuts) live in parallel arrays:

```java
private int[] eFrom, eTo, eChildA, eChildB;   // childA/childB = -1 for an original road
private double[] eW;
```

**Constructor.**

```java
for (int u = 0; u < n; u++)
    for (int e = g.outBegin(u); e < g.outEnd(u); e++)
        if (g.outTarget(e) != u) addEdge(u, g.outTarget(e), g.edgeWeight(e), -1, -1, out, in);  // skip self-loops
rank = new int[n];
shortcuts = contractAll(out, in, witnessSettleLimit);
buildUpwardGraph();
```

The self-loop guard is a real bug fix. A self-loop on v made v its own in- and out-neighbour, so contracting v added edges to the very list being iterated. That list kept growing and preprocessing never ended on Chennai. A regression test (`testSelfLoopsDoNotBreakContraction`) now covers it.

**`contractAll`: choosing the order lazily.**

```java
for (int v = 0; v < n; v++) order.pushOrDecrease(v, importance(v, ...));   // initial priorities
while (!order.isEmpty()) {
    int v = order.pop();
    double imp = importance(v, ...);                       // recompute: neighbours may have changed
    if (!order.isEmpty() && imp > order.peekKey()) {       // no longer the least important?
        order.pushOrDecrease(v, imp); lazy++; continue;    // put it back, try the next one
    }
    added += contract(v, ..., false);                      // really contract it
    done[v] = true;
    rank[v] = next++;                                      // its rank = contraction order
    for each not-done neighbour x of v: deleted[x]++; level[x] = max(level[x], level[v] + 1);
}
```

**Importance** (lower = contract sooner):

```java
return (shortcutsNeeded - edgesRemoved) + deleted[v] + level[v];
```

- *Edge difference*: contracting v should not add more edges than it removes.
- *Deleted neighbours*: spreads contraction evenly across the map.
- *Level*: keeps the hierarchy shallow.

"Lazy updates" means we don't recompute every neighbour after each contraction. We recompute only the top of the heap when we pop it. If it got worse, we push it back.

**`contract(v)`: which shortcuts are needed.**

```java
for each incoming edge a = (u -> v), u not done:
    maxVia = max over outgoing b = (v -> x) of w(a) + w(b)
    w.search(u, skip = v, maxDist = maxVia, limit)        // witness search
    for each outgoing b = (v -> x):
        via = w(a) + w(b)
        if (w.dist(x) <= via) continue                    // another path avoids v: no shortcut
        addEdge(u, x, via, a, b)                          // shortcut remembers children a and b
        w.improve(x, via)                                 // the new shortcut is itself a witness
```

**Witness search** is a small Dijkstra from u that is not allowed to pass through v and stops after `limit` (60) settled nodes or once the distance passes `maxVia`. If it finds a path to x no longer than via, the shortcut is unnecessary.

Correctness does **not** depend on the limit: stopping early only means we add a few extra shortcuts that weren't strictly needed. Extra shortcuts are always safe; missing ones are not, and we never skip one without a witness.

`compact()` removes edges to already-contracted nodes from adjacency lists, so lists stay short.

**`buildUpwardGraph`.** Two CSRs, built with the same counting sort as `RoadGraph`:
- `upOut[u]`: edges u → x with rank[u] < rank[x] (forward search climbs these)
- `upIn[x]`: edges u → x with rank[u] > rank[x], stored at x (backward search walks them from x to u, climbing)

**`route(s, t)`.**

```java
// reset only what the last query touched (O(touched))
setF(s, 0, -1); setB(t, 0, -1);
while (true) {
    boolean fOk = !hf.isEmpty() && hf.peekKey() < best;    // forward can still improve?
    boolean bOk = !hb.isEmpty() && hb.peekKey() < best;
    if (!fOk && !bOk) break;                               // neither can: best is final
    boolean forward = fOk && (!bOk || hf.peekKey() <= hb.peekKey());  // expand the smaller side
    if (forward) {
        int u = hf.pop();
        if (df[u] + db[u] < best) { best = df[u] + db[u]; meet = u; }   // both searches reached u
        for each upward out-edge e = (u -> x): relax df[x] via df[u] + w(e)
    } else { ... the mirror image using upIn ... }
}
```

The stopping rule differs from plain bidirectional Dijkstra. In a CH, the two searches don't meet in the middle by distance; they meet at the *highest-ranked* node on the path. So each side runs until its smallest key is ≥ best, not until the two tops add up to best.

**Path recovery.**

```java
for (v = meet; pf[v] != -1; v = eFrom[pf[v]]) edges.add(pf[v]);   // walk forward parents back to s
reverse(edges);
for (v = meet; pb[v] != -1; v = eTo[pb[v]]) edges.add(pb[v]);     // walk backward parents on to t
for (int e : edges) unpack(e, path);

void unpack(int e, List<Integer> path) {
    if (eChildA[e] < 0) { path.add(eTo[e]); return; }   // an original road: emit its endpoint
    unpack(eChildA[e], path);                           // a shortcut: expand u -> v ...
    unpack(eChildB[e], path);                           // ... then v -> x
}
```

**`export()`** writes rank and all edges with children as arrays. `ExportStatic` puts them in the browser bundle, and `web/engine.js` class `CH` runs the same query in JavaScript. Preprocessing happens offline, queries happen in the browser.

**How correctness is checked (`RoutingTest`).** On random graphs and 300 real Chennai queries, CH, A* and bidirectional must match Dijkstra's cost. The unpacked path must start at s, end at t, and its edge weights must add up to the reported cost.

**Interview Q:** *"What if traffic changes?"* CH bakes weights into shortcuts. For live traffic you'd use Customizable CH: keep the node order, recompute only shortcut weights (seconds, not a full rebuild).

---

## Part 4. Finding nearby things

### 4.1 k-d tree (`ds/KdTree.java`): snap a GPS point to a road node

Used when a caller's location comes in: find the nearest road junction.

- Project lat/lon to metres: `x = lon · 111,320 · cos(lat0)`, `y = lat · 110,540`. Accurate enough at city scale.
- **Build:** at depth d, split on x if d is even, y if odd. Quickselect puts the median at the middle index, smaller ones left, larger ones right. Recurse on both halves. The tree is implicit in an index array (no node objects). O(n log n).
- **Query:** descend to the leaf the point falls in, then backtrack. Visit the other side of a split only if the splitting line is closer than the best distance found so far. O(log n) on average.

The 300 m guard (`Scenario.MAX_SNAP_METERS`) uses it: if the nearest road is more than 300 m away, the point is outside the mapped area and the API returns 422. This fixed pins that snapped across the Adyar river.

### 4.2 Spatial grid (`ds/SpatialGrid.java`): nearest *moving* ambulances

A k-d tree must be rebuilt when points move. Ambulances move all the time, so we use a grid of 500 m cells: **O(1) updates**.

```java
private long key(long cx, long cy) { return (cx << 32) ^ (cy & 0xffffffffL); }  // two ints packed into one long

public void upsert(int id, double lat, double lon) {
    // compute the cell; if the ambulance changed cell, remove it from the old one; add to the new one
}
```

`kNearest(lat, lon, k)` scans **rings** of cells outward: ring 0 is the query's own cell, ring r is the border of the (2r+1)×(2r+1) square (only 8r cells, not the whole square). It stops when it has k results and the k-th is closer than `r × cell`, because anything in a farther ring is at least that far. This is the same idea as Uber's H3 or geohash buckets.

### 4.3 LRU cache (`ds/LruCache.java`)

Hash map for O(1) lookup, doubly linked list for O(1) "most recent" order. Sentinels `head` and `tail` remove edge cases.

- `get`: found → move to front.
- `put`: full → evict `tail.prev` (least recent).

Caches routes keyed by `from * n + to` (one long per pair). Hot pairs like "base 4 → hospital 2" repeat constantly.

---

## Part 5. The dispatch decision (`dispatch/DispatchEngine.java`)

### 5.1 State

- `ambulances`: id → Ambulance (unit type ALS/BLS, node, status, base)
- `available`: a SpatialGrid with only the free units
- `waiting`: a priority queue of unserved incidents, ordered by severity then age
- `timeToHospital`: for each hospital, an array of travel times from every node (precomputed by reverse Dijkstra)
- `routeCache`: LRU of routes

Every method is `synchronized`: one writer per region. This is a design choice, explained in Part 7.

### 5.2 `dispatch(incident)`, line by line

```java
List<Ambulance> cands = candidatesFor(inc);          // grid: k nearest free units by straight line (k = 8)
if (cands.isEmpty()) { waiting.add(inc); return Optional.empty(); }   // nobody free: queue it
for (Ambulance a : cands) {
    Route r = route(a.node, inc.node());             // exact road time (CH, cached)
    if (!r.found()) continue;                        // unreachable (one-way trap)
    double score = policy == NEAREST_STRAIGHT_LINE ? straightLine(...) : r.seconds();
    if (inc.severity() == P1_CRITICAL && a.unit == BLS) score += 180;   // critical: prefer advanced crews
    if (score < bestScore) { ... keep the best ... }
}
return Optional.of(assign(best, inc, bestRoute, reason));
```

The key point: straight-line distance only **shortlists** candidates. The final choice uses **real road time**. That one change is worth +10.1 points in Chennai.

A basic-life-support (BLS) unit must be more than 3 minutes closer than an advanced unit to win a critical call.

`assign()` marks the unit EN_ROUTE, removes it from the grid, picks the hospital and returns the assignment with a human-readable `reason`.

### 5.3 `chooseHospital`

```java
for each hospital i with free beds:
    t = timeToHospital[i][incidentNode]                  // O(1) lookup
    track nearest overall (nt, nearest)
    if it has the needed specialty, track the best capable one (bt, best)
boolean timeCritical = P1 or P2;
if (!timeCritical || best < 0 || bt - nt > 15 min) return nearest;
return best;
```

In words:
- Minor cases (P3/P4) go to the nearest hospital.
- Critical cases go to the nearest hospital that can treat them (stroke unit, cath lab, trauma, burns, maternity)...
- ...unless that is more than 15 minutes further than the nearest one. Then the nearest hospital stabilises the patient first.

This was changed after you noticed patients being sent to a far hospital when closer ones existed.

### 5.4 `release()` and the queue

When a unit finishes, it becomes available at the hospital. If calls are waiting, it takes the most severe, oldest one immediately. Otherwise it drives back to base.

### 5.5 `dispatchBatch()`: surges and the Hungarian algorithm

**Why greedy fails:** calls A and B arrive together. Ambulance X is 2 minutes from A and 3 from B; Y is 4 from A and 20 from B. Greedy gives X to A, then Y to B (22 minutes total). Optimal: X to B, Y to A (7 minutes).

`Hungarian.solve(cost)` finds the assignment with minimum total cost in O(n²m). The cost is road time × a severity weight, so critical calls count more.

How the algorithm works, at the level you need:
- Keep "potentials" u[i] for rows and v[j] for columns, with `cost[i][j] − u[i] − v[j] ≥ 0` (reduced costs).
- Add rows one at a time. For each, grow a tree of alternating paths using only zero-reduced-cost edges (Dijkstra-like, `minv` holds the smallest reduced cost to each column).
- When stuck, shift potentials by the smallest `delta`, which creates a new zero edge.
- When a free column is reached, flip the path (augment).

You should be able to say: "It's a shortest augmenting path method with potentials, like Dijkstra on reduced costs, O(n³)."

---

## Part 6. Where idle ambulances wait (`dispatch/CoverageOptimizer.java`)

### 6.1 The model: MEXCLP

- The city is split into demand zones; zone i has forecast weight w_i (expected calls).
- A base *covers* zone i if it can reach i within the target time.
- Each ambulance is busy with probability q. A zone covered by k ambulances gets a free one with probability 1 − q^k.
- Choose B base slots (several ambulances may share a base) to maximise **Σ w_i (1 − q^{k_i})**.

### 6.2 Why greedy is good enough

Adding an ambulance to a zone that already has k covering it gains `w (1 − q) q^k`, which shrinks as k grows. This "diminishing returns" property is **submodularity**. For a monotone submodular objective, picking the best marginal gain each round gets at least (1 − 1/e) ≈ 63% of the optimum. In brute-force checks on small cases it was within 10%.

### 6.3 Lazy greedy, line by line

```java
for each candidate c: pq.add({gain(c), c, round = 0})
for (round = 0; round < ambulances; round++) {
    while (true) {
        top = pq.poll();
        if (top.round == round) {               // gain computed this round: it is truly the best
            chosen.add(c); for z in covers[c]: k[z]++;
            pq.add({gain(c), c, round + 1});    // c can be chosen again (second ambulance at same base)
            break;
        }
        pq.add({gain(c), c, round});            // stale: recompute and push back
    }
}
```

Gains only go down, so a stale value is an upper bound. If, after refreshing, the top is still on top, nothing else can beat it. This skips most recomputations.

---

## Part 7. System design pieces

### 7.1 Why one writer per region

The danger in dispatch is not throughput (India peaks around 45 calls/s); it is **sending two ambulances to one call, or one ambulance to two calls**. If one thread owns a region's state, every decision is in a single order. No distributed locks are needed.

To scale out, each region is owned by one instance, chosen with a **consistent-hash ring** (`ds/ConsistentHashRing.java`):

```java
addNode(node): for i in 0..virtualNodes: ring.put(md5(node + "#" + i), node)
nodeFor(key):  first ring entry at or after md5(key); wrap around to the first if none
```

Adding or removing an instance moves only about 1/N of the regions. Virtual nodes even out the load. Lookup is O(log V) via `TreeMap.tailMap`.

### 7.2 Write-ahead log (`infra/WriteAheadLog.java`)

```java
out.write(jsonLine); out.write('\n'); out.flush();
if (fsync) channel.force(false);   // optionally force to disk
```

Every incident is appended **before** the API replies. If the process crashes, `replay()` reads the log and rebuilds state. A half-written last line (crash during a write) is ignored because it won't start with `{` and end with `}`. In production you'd batch fsyncs (group commit) or ship the log to Kafka and keep a standby replaying it.

### 7.3 Idempotency keys (`api/Server.postIncident`)

Phones on bad networks retry. Without protection, a retry creates a second incident and sends a second ambulance.

```java
String key = header("Idempotency-Key");
if (key != null && idempotent.get(client + "|" + key) != null) return the stored response (200, Idempotent-Replay: true);
... process ...
idempotent.put(client + "|" + key, response);
```

Result: at-least-once delivery from the client, exactly-once effect on the server.

### 7.4 Token bucket (`ds/TokenBucket.java`)

```java
b[0] = min(capacity, b[0] + (now - last) * ratePerNano);   // refill based on elapsed time
b[1] = now;
if (b[0] >= 1) { b[0] -= 1; return true; }                 // spend a token
return false;                                              // -> HTTP 429
```

Lazy refill: no background thread. Allows bursts up to `capacity`, then the sustained rate. The clock is injectable, so tests are deterministic.

### 7.5 Circuit breaker (`infra/CircuitBreaker.java`)

Wraps calls to the Python triage service. Dispatch must never wait on ML.

- **CLOSED**: calls go through. Count failures.
- After `failureThreshold` failures → **OPEN**: skip the call, use the fallback (rule-based triage) immediately.
- After `coolDownMs` → **HALF_OPEN**: let one trial call through. Success → CLOSED; failure → OPEN again.

The ambulance still goes out, with slightly less smart triage. Each triage result records its `source` (llm or rules), so you can see what happened.

### 7.6 The request path (`api/Server.handle`)

For `POST /api/v1/incidents`:

1. CORS headers; OPTIONS returns early.
2. Rate limit per client (write bucket) → 429.
3. Idempotency check → replay stored response.
4. Parse JSON (hand-written parser, `infra/Json.java`; zero dependencies) → 400 on bad input.
5. Validate lat/lon; snap to the nearest road; > 300 m → 422.
6. `fleet.report(...)`: triage (through the breaker), then `engine.dispatch`.
7. Append to the WAL.
8. Store the idempotent response; reply 201 (dispatched) or 202 (queued).
9. `finally`: count status codes and record latency in `Metrics`.

Other endpoints: `GET /api/v1/route?from&to&algo=` (compare routers live), `/api/v1/state`, `/api/v1/snap`, `/api/v1/info`, `/metrics`, `/health`.

`api/LiveFleet.java` runs the fleet in real time (in the city's time zone), moving ambulances along their routes, with speed 1×/10×/60× for demos.

---

## Part 8. Measuring the effect: the simulator (`sim/`)

### 8.1 Discrete-event simulation

Time does not tick second by second. It **jumps** from event to event, kept in a priority queue ordered by time. A simulated month runs in seconds.

Events and what each does (`Simulator.run`):

| Event | Action |
|---|---|
| ARRIVE | Build the incident; `engine.dispatch`. If assigned, schedule ON_SCENE at now + 60 s (crew gets in) + drive time. |
| ON_SCENE | Record response time. Schedule LEAVE_SCENE after 12–20 min. |
| LEAVE_SCENE | With a probability by severity, transport: schedule AT_HOSPITAL after the drive. Otherwise FREE. |
| AT_HOSPITAL | Schedule FREE after a 15-min handover. |
| FREE | `engine.release`. If a call was waiting, start it. Else schedule BACK_AT_BASE. |
| BACK_AT_BASE | Move the unit back into the grid at its base, **only if** its generation counter still matches. |
| SHIFT | Every 8 h, switch to that shift's base plan. |

**Generation counters.** If a unit heading back to base gets a new call, the old BACK_AT_BASE event is still in the queue. Each new job increments `generation[unit]`; a stale event whose number doesn't match is ignored. This is a cheap way to "cancel" events in a heap.

### 8.2 Calls

`generate()`: for each zone and each hour, draw a Poisson count with that zone's rate for that hour of the week. Poisson sampling uses Knuth's method (multiply uniforms until below e^−λ).

### 8.3 Placements compared

- `HOSPITAL_BASED`: the status quo, crews wait at hospitals.
- `GEOGRAPHIC`: MEXCLP with uniform demand.
- `FORECAST_STATIC`: MEXCLP with forecast demand.
- `FORECAST_PER_SHIFT`: a plan per 8-hour shift, with shrinkage (sparse counts pulled toward the overall pattern).
- `DYNAMIC_MOVE_UP`: re-solve whenever the set of idle units changes, then use Hungarian matching to move units as little as possible. Units within 90 s of their new base just stay.

**Fairness rule:** the optimiser never sees the true rates; it only sees counts from separately simulated history, like a real planner would.

### 8.4 Statistics (`Experiments.java`)

- **Common random numbers:** every policy faces the identical call stream (same seed). Differences come from the policy, not from luck.
- **Paired confidence intervals:** run 10 seeds; for each, compute policy A minus policy B; report the mean difference ± 1.96 × std / √10. If the interval excludes 0, the difference is significant.

**Results (Chennai, 20 ambulances):** status quo 30.8% → coverage placement 68.5% → plus road-ETA dispatch 78.6%. Forecast weighting and per-shift moves were **not** significant. 13 optimised ambulances beat 25 under the status quo.

---

## Part 9. The ML

### 9.1 Demand forecasting (`ml/forecast/pipeline.py`)

**Data:** 48,812 real EMS calls (Montgomery County, PA, 911 dataset).

**Steps:**
1. Map each call to a zone (geohash precision 5, about 5 km cells).
2. Build a panel: one row per (zone, hour), count = calls (0 filled in).
3. Features: hour of day, day of week, hour of week, zone, lat/lng, lags (1, 2, 24, 168, 336 hours), rolling means (24 h, 168 h), same hour over the last 4 weeks. Every lag is shifted, so no feature sees the future.
4. Split by **time**: last 4 weeks are the test set; 3 weeks before that are validation for early stopping. Never a random split on time series: that leaks the future.
5. Model: LightGBM with `objective="poisson"` (the target is a count: non-negative, variance grows with the mean).
6. Compare with baselines: same hour last week, zone × hour-of-week average, and a **separable** model (zone rate × hour-of-week profile).

**Metrics:**
- Poisson deviance (the right loss for counts)
- MAE per zone-hour, WAPE per zone-shift
- **Hotspot capture@5**: of the calls in a shift, what share fell in the 5 zones the model ranked highest. Compared with an oracle that knows the true top 5.

**Result:** LightGBM ties the separable model. Even the oracle captures only 32%: zone-hour counts are mostly noise.

### 9.2 Spatial stability (`spatial_stability.py`)

Question: does *where* calls happen change across the day? Compute each shift's spatial distribution and the total variation distance (half the sum of absolute differences) to the overall one. Compare with what bootstrap resampling of the same size gives by chance. Result: the differences are no bigger than noise. **So per-shift repositioning can't help**, which the simulator then confirmed. Saying "the ML result predicted the simulation result" is a strong interview story.

### 9.3 Transfer to a new city (`transfer.py`)

No 911 data exists for Chennai. We transfer only what generalises: the hour-of-week profile, calls per capita (scaled 0.35× for India, a stated assumption), and the case mix. Spatial demand comes from the new city's residential street density. The script writes `demand.csv` and `case_mix.csv`.

### 9.4 Triage (`ml/triage/`)

- `rules.py`: ordered keyword rules, most critical first; first match wins. Ambiguous wording errs toward higher priority, because under-triage is the dangerous mistake. Java (`Triage.java`) and JavaScript (`engine.js`) have the same rules.
- `llm.py`: Claude reads messy notes ("dad is grey and sweaty, jaw hurts" = heart attack, no keyword). It must answer through a **tool schema with enums only**, so free text never reaches dispatch. Key from the `ANTHROPIC_API_KEY` environment variable only. Any error → rules.
- `service/app.py`: FastAPI wrapper. Java calls it with a 400 ms budget inside the circuit breaker.
- `evaluate.py` with `eval_notes.csv`: measures accuracy and, more importantly, under-triage rate.

---

## Part 10. The browser edition (the live demo)

The Hugging Face Space is static: no Java server. So the browser runs a JavaScript port.

### 10.1 `web/engine.js`

- `Heap`, `Graph` (CSR), `Dijkstra`, `AStar`, `Bidirectional`, `CH`: line-for-line ports of the Java code. The `CH` class builds the upward CSR from the exported arrays (same counting sort) and runs the same query and unpacking.
- `World`: dispatch engine + live fleet. It moves ambulances along their routes in real time, picks hospitals with the same rule (and stores a `hospitalWhy` explanation), and saves a snapshot to localStorage so calls survive a page refresh.
- `loadBundle`: fetch `city.b64`, base64-decode, inflate with `DecompressionStream('gzip')`, parse JSON. About 1 s for a city.
- `useExternal(fn)`: lets Quick mode swap in OSRM for routing.

### 10.2 `web/anywhere.js`: any city in the world

**Full build (`buildCity`):**
1. Fetch roads and hospitals from Overpass, racing 3 mirrors (first to answer wins).
2. Speed per road type.
3. **Kosaraju SCC**: keep only the largest strongly connected component, so every node can reach every other (otherwise an ambulance could be routed into a one-way dead end).
4. Demand zones from street density; MEXCLP for bases; CH preprocessing in the browser.
5. Cache the result in IndexedDB.

**Quick mode (`startQuick`):** start immediately with OSRM (table for "which ambulance is fastest", route for the path) and Nominatim for hospitals, while the full build runs. `maybeSwitchToFull` switches when it's ready.

### 10.3 `web/index.html`: the dashboard

- City picker: 48 prebuilt Indian cities (`data/cities.json`), or any place via build.
- Search with suggestions (Photon), saved addresses with notes for the crew.
- "Your calls": each call's ambulance, countdown, km, current street; the map follows the ambulance.
- Contacts: 108 plus verified hospital phones; otherwise a Google Maps link.
- **Crew app** (`?crew=<id>`, or the in-page driver's phone): a BroadcastChannel carries the job from the dispatcher tab to the crew tab. A Web Audio alarm plays; the crew acknowledges and gets navigation. The audio context is unlocked on the Send click, so no "enable sound" prompt. In production this would be push notifications plus a WebSocket, with radio as a fallback.

### 10.4 How a city bundle is made

`scripts/build_city_bundle.py <city>`:
1. Pick up to 25 hospitals from OSM: prefer known emergency hospitals (MAJOR regex), skip clinics/dental/eye (SKIP regex), must be within 150 m of a road, spread out (minimum gaps 1200 → 700 → 350 m). Capabilities are guessed from the name and flagged as assumptions. Chennai's 14 hospitals are curated by hand.
2. Run `transfer.py` for demand.
3. Run `java -jar goldenhour.jar export-static` → graph, CH, hospitals, bases, zones as JSON.
4. Add country and emergency number; gzip; base64 → `web/data/<city>/city.b64`.
5. Update `web/data/cities.json`.

---

## Part 11. Trace one call, start to finish

Someone in T. Nagar types "my father fell, not breathing" and presses Send.

1. **Browser** reads the pin (lat, lon), checks it's within 300 m of a road (else "outside service area").
2. **Triage**: "not breathing" matches the first rule → P1_CRITICAL, need CARDIAC.
3. **Snap**: k-d tree → nearest road node.
4. **Candidates**: spatial grid → 8 nearest free ambulances by straight line.
5. **Road times**: 8 CH queries (~26 µs each), cached in the LRU.
6. **Score**: lowest road time; BLS units get +180 s for a P1 call. Pick the best.
7. **Hospital**: nearest with CARDIAC, unless it is more than 15 min further than the nearest hospital.
8. **Server only**: WAL append, idempotent response stored, 201 returned.
9. **Crew**: BroadcastChannel → alarm on the crew phone → acknowledge → navigation.
10. **Live tracking**: the ambulance moves along the unpacked CH path; the card shows ETA, km, street.
11. **After**: on scene → hospital → handover → free → back to base (or straight to a waiting call).

Total decision time on the server: p99 386 µs.

---

## Part 12. Exercises (do these to really own it)

1. **Heap:** write `IndexedMinHeap` from memory. Then run `DataStructuresTest`.
2. **CSR:** draw the arrays for a 4-node graph by hand, then check with a tiny `RoadGraph`.
3. **Dijkstra vs CH:** run `bench`. Explain why CH settles ~130 nodes and Dijkstra ~10,800.
4. **Break CH on purpose:** remove the `w.dist(x) <= via` check (add every shortcut). Tests still pass; preprocessing gets slower and queries settle more. Then skip shortcuts randomly: tests fail. Explain why one is safe and the other isn't.
5. **Witness limit:** change 60 to 5 and to 500. Watch shortcut count and query time.
6. **Hungarian:** build the 2×2 example from 5.5 and check it picks 7 minutes, not 22.
7. **Dispatch policy:** in `Experiments`, run only NEAREST_STRAIGHT_LINE vs NEAREST_ROAD_ETA. Reproduce the +10 points.
8. **Circuit breaker:** run the triage service, send calls, stop it. Watch `/metrics` fallbacks rise and the state go OPEN.
9. **Idempotency:** send the same POST twice with the same `Idempotency-Key` using curl. The second reply has `Idempotent-Replay: true` and no second ambulance moves.
10. **Forecast:** add a feature (e.g. holiday flag) and see if Poisson deviance improves.

---

## Part 13. Quick interview answers

- **"Walk me through the architecture."** Java core (routing, dispatch, simulation, API) with zero dependencies; Python sidecar for ML (forecast offline, triage online with fallback); static browser edition for the demo. One writer per region; WAL; consistent hashing to scale.
- **"Most interesting bug?"** CH never finished on Chennai. Debug logging showed one node's degree growing forever. Cause: OSM self-loops made a node its own neighbour, so contracting it appended to the list being iterated. Fix: drop self-loops; regression test.
- **"A negative result?"** Per-shift repositioning didn't help. The spatial stability test explained it: where calls happen doesn't change across the day. So I shipped the simpler static policy.
- **"Complexities?"** Grid k-nearest O(k) expected; CH query ~O(few hundred settled × log); hospital O(H) lookups; Hungarian O(n³) only on surges; MEXCLP lazy greedy ~O(B · Z · log C).
- **"What would you build next?"** Customizable CH with live traffic, hospital bed/diversion feeds, clinician-validated triage, off-policy evaluation on real dispatch logs, shadow-mode rollout.

See `docs/INTERVIEW.md` for more questions and resume bullets.
