# Benchmarks — Chennai

Road graph: 21,336 nodes, 53,090 directed edges. JDK 21, 2 cores. 2,000 random origin–destination pairs, after 500 warm-up queries.

Contraction-hierarchy preprocessing: **1.17 s**, 60,940 shortcuts added (115% of original edges).

| Router | p50 latency | p99 latency | mean nodes settled | speed-up vs Dijkstra (p50) |
|---|---:|---:|---:|---:|
| dijkstra | 1126 µs | 2240 µs | 10,837 | 1.0× |
| a-star | 1140 µs | 3629 µs | 7,285 | 1.0× |
| bidirectional-dijkstra | 674 µs | 1911 µs | 5,911 | 1.7× |
| contraction-hierarchies | 26 µs | 76 µs | 133 | 43.5× |

Dispatch decision (k-nearest filter → 8 exact road ETAs via CH → ALS rule → hospital choice), 40 units, 20,000 incidents single-threaded: **p50 108 µs, p99 386 µs, 6,576 decisions/s** on one core (uncached: every incident is at a new random location).

