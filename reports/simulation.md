# Simulation results

Road graph: 16,411 nodes / 31,503 directed edges (OpenStreetMap). Fleet 8 ambulances (⅓ BLS). Demand: 3.0× the per-capita EMS rate of the real 911 data, 12 weeks × 10 seeds per arm, identical call streams across arms.

| Configuration | P1 median | P1 p90 | **P1 reached ≤ 8 min** | Δ vs previous step (95% CI) | All-call p90 | Utilisation |
|---|---:|---:|---:|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 11.6 min | 22.1 min | **32.0%** | — | 21.7 min | 23% |
| + coverage-optimised bases (uniform demand) | 5.6 min | 13.0 min | **71.3%** | +39.2 pp (+37.8 … +40.7) | 13.0 min | 21% |
| + road-network ETA dispatch (CH routing) | 5.6 min | 12.0 min | **74.9%** | +3.6 pp (+2.9 … +4.4) | 12.1 min | 20% |
| + forecast-weighted bases (MEXCLP) | 5.2 min | 11.9 min | **78.1%** | +3.2 pp (+2.1 … +4.2) | 11.9 min | 20% |
|   alt: re-optimise every 8 h shift | 5.5 min | 12.4 min | **76.2%** | -1.9 pp (-2.9 … -0.8) | 12.3 min | 20% |
|   alt: dynamic move-up of idle units | 5.5 min | 11.8 min | **78.1%** | +0.1 pp (-0.7 … +0.8) | 11.8 min | 20% |

## Fleet size needed

Share of P1 calls reached within 8 minutes as the fleet grows (same demand). Reading across a row shows how many ambulances the optimised system saves for the same service level.

| Configuration | 4 | 5 | 6 | 7 | 8 | 10 |
|---|---:|---:|---:|---:|---:|---:|
| Status quo: crews wait at hospitals, nearest unit by straight line | 20.2% | 23.5% | 24.9% | 26.4% | 32.2% | 36.1% |
| + coverage-optimised bases (uniform demand) | 38.2% | 53.5% | 61.5% | 60.6% | 71.9% | 73.2% |
| + road-network ETA dispatch (CH routing) | 40.4% | 56.9% | 66.4% | 70.7% | 75.2% | 78.4% |
| + forecast-weighted bases (MEXCLP) | 40.9% | 56.9% | 65.7% | 73.8% | 78.4% | 84.9% |
|   alt: dynamic move-up of idle units | 40.0% | 56.5% | 65.4% | 73.2% | 78.6% | 84.4% |
