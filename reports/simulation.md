# Simulation results — Chennai

Road graph: 21,336 nodes / 53,090 directed edges (OpenStreetMap). Fleet 20 ambulances (⅓ BLS). Demand: 0.35× the per-capita EMS rate of the real 911 data, 12 weeks × 10 seeds per arm, identical call streams across arms.

| Configuration | P1 median | P1 p90 | **P1 reached ≤ 8 min** | Δ vs previous step (95% CI) | All-call p90 | Utilisation |
|---|---:|---:|---:|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 10.1 min | 18.7 min | **36.8%** | — | 17.8 min | 25% |
| + coverage-optimised bases (uniform demand) | 6.6 min | 11.9 min | **68.5%** | +31.7 pp (+30.7 … +32.8) | 10.4 min | 23% |
| + road-network ETA dispatch (CH routing) | 6.1 min | 9.8 min | **79.2%** | +10.7 pp (+10.2 … +11.1) | 9.5 min | 23% |
| + forecast-weighted bases (MEXCLP) | 6.3 min | 9.6 min | **78.9%** | -0.2 pp (-1.3 … +0.8) | 9.3 min | 23% |
|   alt: re-optimise every 8 h shift | 6.3 min | 9.8 min | **78.1%** | -0.8 pp (-2.1 … +0.5) | 9.6 min | 23% |
|   alt: dynamic move-up of idle units | 6.2 min | 9.6 min | **79.6%** | +0.7 pp (-0.2 … +1.5) | 9.4 min | 23% |

## Fleet size needed

Share of P1 calls reached within 8 minutes as the fleet grows (same demand). Reading across a row shows how many ambulances the optimised system saves for the same service level.

| Configuration | 10 | 13 | 15 | 18 | 20 | 25 |
|---|---:|---:|---:|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 17.3% | 25.9% | 28.9% | 32.8% | 36.6% | 41.8% |
| + coverage-optimised bases (uniform demand) | 29.6% | 44.1% | 52.8% | 60.1% | 68.8% | 75.9% |
| + road-network ETA dispatch (CH routing) | 35.5% | 54.5% | 64.3% | 75.5% | 79.3% | 86.7% |
| + forecast-weighted bases (MEXCLP) | 34.7% | 55.6% | 65.5% | 74.7% | 79.1% | 87.4% |
|   alt: dynamic move-up of idle units | 34.2% | 53.4% | 62.7% | 74.4% | 79.7% | 88.1% |
