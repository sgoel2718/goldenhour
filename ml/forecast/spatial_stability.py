"""Does WHERE emergencies happen change with the time of day? (Decides whether per-shift re-positioning can pay off.)

For each 8-hour shift of the day we compare its zone distribution with the all-day distribution (total variation
distance), and compare that against the distance expected from sampling noise alone (bootstrap: draw the same
number of calls from the all-day distribution). If observed ≈ noise, the spatial pattern is stable and moving
ambulances between shifts only buys churn — which is what the simulator shows.
"""
import json

import numpy as np

from pipeline import ROOT, load_calls

d = load_calls()
d = d[d.zone.isin(d.zone.value_counts()[lambda s: s >= 50].index)]
zones = sorted(d.zone.unique())
overall = d.zone.value_counts(normalize=True).reindex(zones).to_numpy()
rng = np.random.default_rng(0)
out = {}
for name, hours in {"night 00-08": range(0, 8), "day 08-16": range(8, 16), "evening 16-24": range(16, 24)}.items():
    s = d[d.timeStamp.dt.hour.isin(hours)]
    obs = s.zone.value_counts(normalize=True).reindex(zones, fill_value=0).to_numpy()
    tvd = 0.5 * np.abs(obs - overall).sum()
    noise = [0.5 * np.abs(rng.multinomial(len(s), overall) / len(s) - overall).sum() for _ in range(200)]
    out[name] = {"calls": int(len(s)), "tvd_observed": round(float(tvd), 4), "tvd_noise_mean": round(float(np.mean(noise)), 4),
                 "tvd_noise_p99": round(float(np.percentile(noise, 99)), 4)}
    print(name, out[name])
path = ROOT / "reports/forecast_metrics.json"
rep = json.loads(path.read_text())
rep["spatial_stability_by_shift"] = out
path.write_text(json.dumps(rep, indent=2))
