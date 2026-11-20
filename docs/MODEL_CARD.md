# Model card

## Demand forecast

| | |
|---|---|
| **Task** | Expected EMS calls per zone per hour (zone = geohash-5 cell, ≈ 4.9 km) |
| **Data** | 48,812 EMS calls, Montgomery County PA 911 dispatch, Dec 2015 – Aug 2016 (public, anonymised). Calls outside the county bounding box were removed. 81 zones with ≥ 50 calls each |
| **Split** | Time-based. Train on everything before the last 4 weeks, test on those 4 weeks. Early-stopping validation uses the 3 weeks just before the test window. No random shuffling |
| **Features** | Hour, weekday, hour-of-week, zone id, centroid, lags (1, 2, 24, 168, 336 h), rolling 24 h / 168 h means, mean of the same hour over the last 4 weeks. All computed from the past only |
| **Models** | Seasonal naive · zone × hour-of-week average · separable (zone rate × hour-of-week profile) · LightGBM with a Poisson objective |
| **Metrics** | Poisson deviance, the proper scoring rule for counts. MAE per zone-hour. WAPE per zone and 8-hour shift, the granularity planning uses. Hotspot capture@5, the share of next-shift calls in the 5 zones ranked highest, compared with an oracle |
| **Result** | LightGBM 0.4009 vs separable 0.4012 Poisson deviance (a tie). Both beat the average (0.472) and the naive baseline (1.116). The oracle captures only 32% at k = 5, so most variation at this granularity is irreducible noise |
| **Decision** | Ship the **separable model**. It is equally accurate, has 2 parameter groups, is explainable to EMS planners, and transfers to cities with no call history |
| **Spatial stability** | The zone distribution of each 8 h shift is compared with the all-day distribution (TVD) and with 200 bootstrap draws from the all-day distribution. The excess over noise is only about 0.4–1.5 percentage points. The simulator confirms that per-shift repositioning does not pay |

**Transfer to a new city:** the hour-of-week profile, the per-capita weekly EMS rate and the case mix come from the 911 data. The spatial share comes from each cell's residential road length in OpenStreetMap, used as a population proxy. **Limitation:** this assumes the US county's per-capita rate and temporal shape apply to the target city. Calibrate with local call logs before operational use.

**Leakage checks:** every lag and rolling feature is shifted so it uses only past hours. The model is refit on all training weeks using the iteration count chosen on validation. Test weeks are never seen.

## Triage

| | |
|---|---|
| **Task** | Free-text call notes → priority (P1–P4) and needed hospital capability |
| **Rules (rules-v1)** | Ordered keyword rules, most critical first. Deterministic and always available |
| **LLM** | Claude via tool-calling with an enum-only schema (`ANTHROPIC_API_KEY` env var). The system prompt says: "when in doubt choose the higher priority" |
| **Evaluation set** | 38 realistic call notes labelled by priority (`ml/triage/eval_notes.csv`), including indirect descriptions and a French note. Written before evaluation and not used to tune the rules |
| **Primary metric** | **P1 recall and the under-triage rate.** Missing a cardiac arrest is not comparable to over-responding to a sprain |
| **Rules result** | Accuracy 0.58, **P1 recall 0.50, under-triage 29%** |
| **Safety design** | The LLM output is validated against enums, with a 400 ms budget and a circuit breaker, and falls back to rules. The source of every decision is recorded. The dispatcher never waits on the model |
| **Limitations** | The evaluation set is small and written by the author. It is not clinical validation. A deployed system would be tested against protocol-coded calls (e.g. MPDS determinants) with clinician review |

## Simulation used for policy evaluation
A discrete-event simulator generates Poisson calls from the transferred demand model and runs the full dispatch engine (the same code as production). It models a 60 s chute time, a 12–20 min scene time, transport to hospital by severity, and a 15 min handover. Every arm sees **identical call streams**, and differences are reported as paired 95% confidence intervals over 10 seeds. The optimiser plans only from 4 weeks of separately simulated *history*, never from the true rates.
