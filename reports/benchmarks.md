# Benchmarks

Road graph: 16,411 nodes, 31,503 directed edges. JDK 21, 2 cores. 2,000 random origin–destination pairs, after 500 warm-up queries.

Contraction-hierarchy preprocessing: **0.07 s**, 30,065 shortcuts added (95% of original edges).

| Router | p50 latency | p99 latency | mean nodes settled | speed-up vs Dijkstra (p50) |
|---|---:|---:|---:|---:|
| dijkstra | 480 µs | 959 µs | 7,942 | 1.0× |
| a-star | 620 µs | 1989 µs | 5,517 | 0.8× |
| bidirectional-dijkstra | 368 µs | 813 µs | 5,194 | 1.3× |
| contraction-hierarchies | 14 µs | 57 µs | 28 | 33.5× |

Dispatch decision (k-nearest filter → 8 exact road ETAs via CH → ALS rule → hospital choice), 40 units, 20,000 incidents single-threaded: **p50 67 µs, p99 377 µs, 7,026 decisions/s** on one core (uncached: every incident is at a new random location).

