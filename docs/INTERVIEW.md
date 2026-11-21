# Resume bullets and interview guide

## Resume bullets (pick 3–4)

- **Built GoldenHour, a real-time ambulance dispatch and pre-positioning platform** (Java 21 core, Python ML). In a simulation on **Chennai's real road network** (21k junctions, OpenStreetMap) it raised the share of critical calls reached within 8 minutes from **31% to 79% with the same 20-ambulance fleet**. 13 optimised ambulances outperform 25 under the status-quo policy.
- **Implemented contraction hierarchies from scratch**, giving exact shortest paths **43× faster than Dijkstra** on Chennai (p50 26 µs, 133 nodes settled instead of 10,800). That brings the full dispatch decision to **p99 386 µs, about 6.5k decisions/s per core**. Found and fixed a non-terminating contraction caused by self-loops in real OSM data, now covered by a regression test. Verified against Dijkstra, A* and bidirectional search on randomised and real graphs.
- **Designed a fault-tolerant dispatch service**: single-writer regional shards on a consistent-hash ring, a write-ahead log, idempotency keys, token-bucket rate limiting, and a circuit-breaker fallback around LLM triage. The service has zero third-party dependencies, 24 tests, CI and Docker.
- **Trained and evaluated LightGBM Poisson demand forecasting on 48,812 real 911 EMS calls** with a time-based holdout. Showed it ties a separable baseline, and statistically showed that the spatial pattern of calls is stable across shifts. This explained a negative simulation result and led to a simpler production policy.
- **Formulated ambulance base placement as MEXCLP** (submodular expected coverage) and solved it with lazy greedy (a (1 − 1/e) guarantee, within 10% of the optimum in brute-force checks). Used the Hungarian algorithm for globally optimal batch dispatch during surges.

## 60-second pitch

"Ambulance response time is mostly lost to decisions, not driving: sending the closest unit *by straight line*, and keeping crews parked at hospitals. I built a dispatch engine that uses exact road travel times from contraction hierarchies, which I implemented myself. It decides in under half a millisecond. Idle crews are placed with a coverage optimiser fed by a demand forecast trained on real 911 data. To measure the effect without patients, I wrote a discrete-event simulator. It replays identical call streams through each policy and reports paired confidence intervals. On Chennai's road network, critical calls reached in 8 minutes went from 31% to 79% with the same fleet. Two ideas I expected to help, shift-by-shift repositioning and continuous move-up, didn't. The forecasting analysis showed why: where emergencies happen is stable across the day. Comparing two cities also showed the demand forecast only pays off where population is concentrated (+3.2 pp in Andorra, nothing measurable in uniformly dense Chennai). So I shipped the simpler policy and kept the forecast where it earns its place."

## Questions to be ready for

**Why contraction hierarchies and not A\*?**
I measured it. On Chennai A* settles about a third fewer nodes but is no faster, because a heuristic that stays admissible for the fastest arterial is weak on 15 km/h congested streets, and evaluating it costs time. CH costs 0.7 s of preprocessing and is then 43× faster. The trade-off is that edge weights are baked into the preprocessing. For live traffic I would move to Customizable CH.

**How do you know CH is correct?**
Property tests compare it with Dijkstra on random geometric graphs and on 300 real-network queries, checking cost, endpoints, and that the weights along the unpacked path add up to the cost. Shortcuts record their two child edges, so paths unpack exactly.

**Why does road-ETA dispatch matter more in Chennai than Andorra?**
One-way streets, flyovers and two rivers make straight-line distance a poor proxy in a dense grid: +10.1 pp of critical calls in Chennai against +3.6 pp in Andorra's open valleys.

**Why not run everything in a database with transactions?**
Peak national load is about 45 calls/s. Throughput is not the problem; tail latency and avoiding double dispatch are. A single writer per region gives a total order of decisions with no distributed locks. Regions are sharded with consistent hashing. Durability comes from a WAL written before the acknowledgement, and a standby replays it.

**What happens if the ML service goes down?**
Nothing visible to dispatch. Triage has a 400 ms budget inside a circuit breaker and falls back to deterministic rules. Each result records its source. This is tested with a fake sidecar that is then stopped.

**Your LightGBM didn't beat the baseline. Isn't that a failure?**
It's a result. Poisson deviance and hotspot capture show that zone-hour counts are mostly noise: an oracle that knows the true top 5 zones only captures 32%. The separable model is just as accurate, explainable and transferable. The same analysis predicted, correctly, that per-shift repositioning wouldn't help.

**How did you avoid fooling yourself in the simulation?**
Common random numbers (every arm sees the same calls), paired confidence intervals over 10 seeds, and the optimiser plans from simulated *history*, never from the true rates. The baseline is a realistic status quo, and I also report the strong intermediate baseline: coverage-optimised placement with uniform demand.

**The calls are from Pennsylvania but the roads are Chennai?**
Yes, and I say so in the README. The ML is validated on the real 911 data. Only what generalises is transferred: the hour-of-week profile, the per-capita rate and the case mix. Spatial demand in the new city comes from its own residential street density. `build_road_graph.py --point lat,lon` makes it run on any city. Chennai's per-capita EMS demand is scaled to 0.35× the US rate, a stated assumption.

**What's the complexity of dispatch?**
Grid k-nearest is O(k) expected. Then k CH queries take about 15 µs each. Hospital choice is O(H) lookups into precomputed reverse trees. The queue is O(log n). Batch dispatch with the Hungarian algorithm is O(n³) on the surge set only.

**How would you scale to a whole country?**
One engine per region (1–5 k units), placed by a consistent-hash ring, with WAL replication to a standby. GPS pings (about 10 k/s nationally) are partitioned by region. Cross-border incidents use a two-phase reservation between the two region writers. CH memory is 0.3–1.5 GB per region.

**What would you do next?**
Customizable CH with live traffic, hospital bed and diversion feeds, protocol-coded triage evaluation with clinicians, and off-policy evaluation on real dispatch logs before a shadow-mode rollout.
