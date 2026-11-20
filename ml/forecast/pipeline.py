"""Emergency-demand forecasting on real 911 calls (Montgomery County, PA — 99,492 calls, Dec 2015–Aug 2016).

Question the dispatcher needs answered every shift: *how many EMS calls will each zone get in the next hours?*
Pre-positioning ambulances only helps if this is predictable — so we test that rigorously before using it.

Pipeline
  1. Clean: EMS calls inside the county bounding box; map each call title to a triage priority + specialty
     (this also yields the real case mix used by the simulator).
  2. Zone × hour panel: zone = geohash-5 cell (~4.9 km); one row per zone per hour, zero-filled.
  3. Features: calendar (hour, weekday, hour-of-week), lags (1 h, 24 h, 168 h), rolling means, zone history —
     all computed from the past only (no leakage).
  4. Model: LightGBM with a Poisson objective (counts!), time-based split: last 4 weeks held out.
  5. Evaluation vs two honest baselines (seasonal naive and zone×hour-of-week average) on
     - Poisson deviance and MAE per zone-hour,
     - WAPE per zone-shift (8 h), the granularity dispatch actually plans at,
     - hotspot capture@k: share of next-shift calls landing in the k zones the model ranks highest.
Outputs: models/forecast.txt, reports/forecast_metrics.json, data/processed/how_profile.csv, data/processed/case_mix_911.csv
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "data" / "raw" / "911.csv"
URL = "https://raw.githubusercontent.com/roheetnarayanan/911-Analysis/master/911.csv"   # public mirror of the Kaggle dataset
BOX = dict(lat=(39.95, 40.45), lng=(-75.70, -74.95))                                      # Montgomery County, PA
BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

# title -> (priority, specialty). P1 = immediately life-threatening ... P4 = non-urgent.
TRIAGE = {
    "CARDIAC ARREST": ("P1_CRITICAL", "CARDIAC"), "CARDIAC EMERGENCY": ("P1_CRITICAL", "CARDIAC"), "CVA/STROKE": ("P1_CRITICAL", "STROKE"),
    "UNCONSCIOUS SUBJECT": ("P1_CRITICAL", "GENERAL"), "UNRESPONSIVE SUBJECT": ("P1_CRITICAL", "GENERAL"), "HEMORRHAGING": ("P1_CRITICAL", "TRAUMA"),
    "CHOKING": ("P1_CRITICAL", "GENERAL"), "SHOOTING": ("P1_CRITICAL", "TRAUMA"), "STABBING": ("P1_CRITICAL", "TRAUMA"), "DROWNING": ("P1_CRITICAL", "GENERAL"),
    "AMPUTATION": ("P1_CRITICAL", "TRAUMA"),
    "RESPIRATORY EMERGENCY": ("P2_EMERGENT", "GENERAL"), "SEIZURES": ("P2_EMERGENT", "GENERAL"), "OVERDOSE": ("P2_EMERGENT", "GENERAL"),
    "ALTERED MENTAL STATUS": ("P2_EMERGENT", "STROKE"), "DIABETIC EMERGENCY": ("P2_EMERGENT", "GENERAL"), "ALLERGIC REACTION": ("P2_EMERGENT", "GENERAL"),
    "VEHICLE ACCIDENT": ("P2_EMERGENT", "TRAUMA"), "HEAD INJURY": ("P2_EMERGENT", "TRAUMA"), "MATERNITY": ("P2_EMERGENT", "MATERNITY"),
    "BURN VICTIM": ("P2_EMERGENT", "BURNS"), "BUILDING FIRE": ("P2_EMERGENT", "BURNS"), "POISONING": ("P2_EMERGENT", "GENERAL"),
    "HEAT EXHAUSTION": ("P2_EMERGENT", "GENERAL"), "ASSAULT VICTIM": ("P2_EMERGENT", "TRAUMA"), "SYNCOPAL EPISODE": ("P2_EMERGENT", "CARDIAC"),
    "FALL VICTIM": ("P3_URGENT", "TRAUMA"), "FRACTURE": ("P3_URGENT", "TRAUMA"), "SUBJECT IN PAIN": ("P3_URGENT", "GENERAL"),
    "ABDOMINAL PAINS": ("P3_URGENT", "GENERAL"), "UNKNOWN MEDICAL EMERGENCY": ("P3_URGENT", "GENERAL"), "GENERAL WEAKNESS": ("P3_URGENT", "GENERAL"),
    "NAUSEA/VOMITING": ("P3_URGENT", "GENERAL"), "DIZZINESS": ("P3_URGENT", "GENERAL"), "BACK PAINS/INJURY": ("P3_URGENT", "TRAUMA"),
    "LACERATIONS": ("P3_URGENT", "TRAUMA"), "FEVER": ("P3_URGENT", "GENERAL"), "DEHYDRATION": ("P3_URGENT", "GENERAL"),
    "ANIMAL BITE": ("P3_URGENT", "TRAUMA"), "EYE INJURY": ("P3_URGENT", "TRAUMA"),
    "MEDICAL ALERT ALARM": ("P4_ROUTINE", "GENERAL"), "EMS SPECIAL SERVICE": ("P4_ROUTINE", "GENERAL"), "CARBON MONOXIDE DETECTOR": ("P4_ROUTINE", "GENERAL"),
}


def geohash(lat: float, lon: float, precision: int = 5) -> str:
    la, lo, out, even, bit, ch = [-90.0, 90.0], [-180.0, 180.0], [], True, 0, 0
    while len(out) < precision:
        r, v = (lo, lon) if even else (la, lat)
        mid = (r[0] + r[1]) / 2
        if v >= mid:
            ch |= 1 << (4 - bit); r[0] = mid
        else:
            r[1] = mid
        even, bit = not even, bit + 1
        if bit == 5:
            out.append(BASE32[ch]); bit, ch = 0, 0
    return "".join(out)


def load_calls() -> pd.DataFrame:
    if not RAW.exists():
        RAW.parent.mkdir(parents=True, exist_ok=True)
        pd.read_csv(URL).to_csv(RAW, index=False)
    d = pd.read_csv(RAW, parse_dates=["timeStamp"])
    d = d[d.title.str.startswith("EMS:") & d.lat.between(*BOX["lat"]) & d.lng.between(*BOX["lng"])].copy()
    d["type"] = d.title.str[4:].str.strip()
    tri = d["type"].map(TRIAGE)
    d["priority"] = tri.map(lambda x: x[0] if isinstance(x, tuple) else "P3_URGENT")
    d["need"] = tri.map(lambda x: x[1] if isinstance(x, tuple) else "GENERAL")
    d["zone"] = [geohash(a, b, 5) for a, b in zip(d.lat, d.lng)]
    d["hour"] = d.timeStamp.dt.floor("h")
    return d


def case_mix(d: pd.DataFrame) -> pd.DataFrame:
    m = d.groupby(["type", "priority", "need"]).size().rename("n").reset_index().sort_values("n", ascending=False)
    m["share"] = m.n / m.n.sum()
    return m


def panel(d: pd.DataFrame, min_calls: int = 50) -> pd.DataFrame:
    keep = d.zone.value_counts()[lambda s: s >= min_calls].index                 # drop near-empty edge cells
    d = d[d.zone.isin(keep)]
    hours = pd.date_range(d.hour.min(), d.hour.max(), freq="h")
    counts = d.groupby(["zone", "hour"]).size()
    idx = pd.MultiIndex.from_product([sorted(keep), hours], names=["zone", "hour"])
    p = counts.reindex(idx, fill_value=0).rename("y").reset_index()
    cent = d.groupby("zone")[["lat", "lng"]].mean()
    p = p.join(cent, on="zone")
    p["how"] = p.hour.dt.dayofweek * 24 + p.hour.dt.hour
    p["hod"], p["dow"] = p.hour.dt.hour, p.hour.dt.dayofweek
    p["zone_id"] = p.zone.astype("category").cat.codes
    g = p.groupby("zone").y
    for lag in (1, 2, 24, 168, 336):
        p[f"lag{lag}"] = g.shift(lag)
    p["roll24"] = g.transform(lambda s: s.shift(1).rolling(24).mean())
    p["roll168"] = g.transform(lambda s: s.shift(1).rolling(168).mean())
    p["same_how_4w"] = sum(g.shift(168 * k) for k in (1, 2, 3, 4)) / 4                # same hour, last 4 weeks
    return p.dropna().reset_index(drop=True)


FEATURES = ["hod", "dow", "how", "zone_id", "lat", "lng", "lag1", "lag2", "lag24", "lag168", "lag336", "roll24", "roll168", "same_how_4w"]


def poisson_deviance(y, mu):
    mu = np.clip(mu, 1e-9, None)
    ratio = np.where(y > 0, y / mu, 1.0)
    return float(2 * np.mean(y * np.log(ratio) - (y - mu)))


def evaluate(test: pd.DataFrame, preds: dict, k: int = 5) -> dict:
    out = {}
    t = test.assign(shift=test.hour.dt.floor("8h"))
    for name, mu in preds.items():
        t["mu"] = mu
        zs = t.groupby(["shift", "zone"])[["y", "mu"]].sum()
        cap = []
        for _, grp in zs.groupby(level=0):
            top = grp.mu.nlargest(k).index
            oracle = grp.y.nlargest(k).sum()
            if grp.y.sum() > 0:
                cap.append((grp.loc[top, "y"].sum() / grp.y.sum(), oracle / grp.y.sum()))
        cap = np.array(cap)
        out[name] = {"poisson_deviance": round(poisson_deviance(test.y.to_numpy(), mu), 4), "mae_zone_hour": round(float(np.mean(np.abs(test.y - mu))), 4),
                     "wape_zone_shift": round(float(np.abs(zs.y - zs.mu).sum() / zs.y.sum()), 4),
                     f"hotspot_capture@{k}": round(float(cap[:, 0].mean()), 4), f"oracle_capture@{k}": round(float(cap[:, 1].mean()), 4)}
    return out


def main():
    d = load_calls()
    print(f"EMS calls in county: {len(d):,} | zones: {d.zone.nunique()} | {d.timeStamp.min():%Y-%m-%d} → {d.timeStamp.max():%Y-%m-%d}")
    os.makedirs(ROOT / "data" / "processed", exist_ok=True)
    os.makedirs(ROOT / "models", exist_ok=True)
    os.makedirs(ROOT / "reports", exist_ok=True)
    cm = case_mix(d); cm.to_csv(ROOT / "data/processed/case_mix_911.csv", index=False)
    print("priority mix:", cm.groupby("priority").share.sum().round(3).to_dict())
    p = panel(d)
    cut = p.hour.max() - pd.Timedelta(weeks=4)
    tr, te = p[p.hour <= cut], p[p.hour > cut]
    val_cut = cut - pd.Timedelta(weeks=3)
    fit, val = tr[tr.hour <= val_cut], tr[tr.hour > val_cut]
    params = dict(objective="poisson", learning_rate=0.03, num_leaves=31, min_data_in_leaf=200, feature_fraction=0.8,
                  bagging_fraction=0.8, bagging_freq=1, lambda_l2=1.0, verbose=-1, seed=0)
    dtrain = lgb.Dataset(fit[FEATURES], fit.y, categorical_feature=["zone_id"])
    dval = lgb.Dataset(val[FEATURES], val.y, categorical_feature=["zone_id"], reference=dtrain)
    model = lgb.train(params, dtrain, 3000, valid_sets=[dval], callbacks=[lgb.early_stopping(100, verbose=False)])
    best = model.best_iteration
    model = lgb.train(params, lgb.Dataset(tr[FEATURES], tr.y, categorical_feature=["zone_id"]), best)   # refit on all training weeks
    model.save_model(str(ROOT / "models/forecast.txt"))
    zone_how = tr.groupby(["zone", "how"]).y.mean()
    # Baselines a real EMS planner would use. Means are floored at 1% of the zone's mean so an unseen
    # zone-hour isn't predicted as exactly zero (which would make Poisson deviance infinite and flatter the model).
    zone_mean = tr.groupby("zone").y.mean()
    floor = 0.01 * zone_mean.reindex(te.zone).to_numpy()
    how_mult = tr.groupby("how").y.mean() / tr.y.mean()
    preds = {"seasonal_naive (same hour last week)": np.maximum(te.lag168.to_numpy(), floor),
             "historical_avg (zone x hour-of-week)": np.maximum(zone_how.reindex(pd.MultiIndex.from_arrays([te.zone, te.how])).fillna(0).to_numpy(), floor),
             "separable (zone rate x hour-of-week profile)": zone_mean.reindex(te.zone).to_numpy() * how_mult.reindex(te.how).to_numpy(),
             "lightgbm_poisson": model.predict(te[FEATURES])}
    metrics = evaluate(te, preds)
    imp = dict(sorted(zip(FEATURES, model.feature_importance("gain").round(0).tolist()), key=lambda kv: -kv[1]))
    report = {"calls": int(len(d)), "zones": int(p.zone.nunique()), "train_rows": int(len(tr)), "test_rows": int(len(te)),
              "test_period": [str(te.hour.min()), str(te.hour.max())], "best_iteration": int(best), "metrics": metrics, "feature_gain": imp}
    (ROOT / "reports/forecast_metrics.json").write_text(json.dumps(report, indent=2))
    for name, m in metrics.items():
        print(f"  {name:40s} {m}")
    # hour-of-week profile (mean = 1) from ALL EMS calls: the temporal shape used for the demo city
    weeks = (d.timeStamp.max() - d.timeStamp.min()).days / 7
    how = d.groupby(d.timeStamp.dt.dayofweek * 24 + d.timeStamp.dt.hour).size().reindex(range(168), fill_value=0)
    prof = pd.DataFrame({"how": range(168), "multiplier": (how / how.mean()).round(4), "calls_per_week": (how / weeks).round(3)})
    prof.to_csv(ROOT / "data/processed/how_profile.csv", index=False)
    print(f"weekly EMS volume {len(d) / weeks:.0f} calls; peak/trough hour-of-week ratio {prof.multiplier.max() / prof.multiplier.min():.1f}")


if __name__ == "__main__":
    main()
