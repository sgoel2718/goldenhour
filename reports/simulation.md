# Simulation results — Chennai

Road graph: 21,336 nodes / 53,090 directed edges (OpenStreetMap). Fleet 20 ambulances (⅓ BLS). Demand: 0.35× the per-capita EMS rate of the real 911 data, 12 weeks × 10 seeds per arm, identical call streams across arms.

| Configuration | P1 median | P1 p90 | **P1 reached ≤ 8 min** | Δ vs previous step (95% CI) | All-call p90 | Utilisation |
|---|---:|---:|---:|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 10.2 min | 19.3 min | **30.8%** | — | 18.9 min | 25% |
| + coverage-optimised bases (uniform demand) | 6.6 min | 12.0 min | **68.5%** | +37.7 pp (+36.8 … +38.6) | 10.4 min | 23% |
| + road-network ETA dispatch (CH routing) | 6.1 min | 9.9 min | **78.6%** | +10.1 pp (+9.8 … +10.5) | 9.6 min | 23% |
| + forecast-weighted bases (MEXCLP) | 6.3 min | 9.7 min | **78.1%** | -0.5 pp (-1.7 … +0.7) | 9.5 min | 23% |
|   alt: re-optimise every 8 h shift | 6.3 min | 9.9 min | **77.3%** | -0.9 pp (-2.1 … +0.4) | 9.7 min | 23% |
|   alt: dynamic move-up of idle units | 6.2 min | 9.8 min | **78.5%** | +0.3 pp (-0.6 … +1.3) | 9.5 min | 23% |

## Fleet size needed

Share of P1 calls reached within 8 minutes as the fleet grows (same demand). Reading across a row shows how many ambulances the optimised system saves for the same service level.

| Configuration | 10 | 13 | 15 | 18 | 20 | 25 |
|---|---:|---:|---:|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 17.0% | 24.7% | 25.6% | 28.1% | 30.7% | 32.3% |
| + coverage-optimised bases (uniform demand) | 29.5% | 43.6% | 52.5% | 60.0% | 68.8% | 75.5% |
| + road-network ETA dispatch (CH routing) | 34.7% | 53.9% | 63.5% | 74.6% | 79.0% | 86.2% |
| + forecast-weighted bases (MEXCLP) | 34.1% | 54.1% | 64.1% | 73.6% | 78.5% | 87.1% |
|   alt: dynamic move-up of idle units | 33.3% | 52.6% | 61.9% | 73.5% | 78.6% | 87.1% |
