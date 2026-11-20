"""Transfer what we learned on real 911 data to the demo city (whose road graph comes from OpenStreetMap).

We do NOT pretend Montgomery County calls happened in the demo city. We transfer three things that generalise:
  * temporal shape  — hour-of-week multiplier profile (4.4x peak/trough) from the real EMS calls,
  * volume          — EMS calls per resident per week from the real county (≈1,330 calls / 830k people),
  * case mix        — share of each call type with its triage priority and needed specialty,
and estimate *where* people live from the city's own map: residential street length per ~1.2 km cell
(a standard population proxy when census rasters are unavailable).

Run any city:  build_road_graph.py --place "<city>" --out data/<city>  then  transfer.py --city data/<city> --population N
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from pipeline import geohash, ROOT

SOURCE_POPULATION = 830_000          # Montgomery County, PA (2016 estimate)
HOMES = {"residential": 1.0, "living_street": 1.0, "unclassified": 0.5, "tertiary": 0.4, "secondary": 0.2, "service": 0.1}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--city", default=str(ROOT / "data" / "city"))
    ap.add_argument("--population", type=int, default=80_000)       # Andorra, residents
    ap.add_argument("--precision", type=int, default=6)
    a = ap.parse_args()
    city = Path(a.city)
    nodes = pd.read_csv(city / "nodes.csv")
    edges = pd.read_csv(city / "edges.csv")
    edges["w"] = edges.road_class.map(HOMES).fillna(0) * edges.length_m / 2      # both directions present -> /2
    edges = edges.join(nodes.set_index("id")[["lat", "lon"]], on="from")
    edges["zone"] = [geohash(x, y, a.precision) for x, y in zip(edges.lat, edges.lon)]
    w = edges.groupby("zone").w.sum()
    w = w[w > w.sum() * 0.002]                                                     # drop empty mountainside cells
    share = w / w.sum()
    nodes["zone"] = [geohash(x, y, a.precision) for x, y in zip(nodes.lat, nodes.lon)]
    cent = nodes[nodes.zone.isin(share.index)].groupby("zone")[["lat", "lon"]].median()

    prof = pd.read_csv(ROOT / "data/processed/how_profile.csv")
    src_weekly = prof.calls_per_week.sum()
    weekly = src_weekly / SOURCE_POPULATION * a.population
    rows = []
    for z, s in share.items():
        for how, m in zip(prof.how, prof.multiplier):
            rows.append((z, cent.lat[z], cent.lon[z], how, s * weekly / 168 * m))
    demand = pd.DataFrame(rows, columns=["zone", "lat", "lon", "hour_of_week", "rate"])
    demand.round({"lat": 6, "lon": 6, "rate": 6}).to_csv(city / "demand.csv", index=False)

    cm = pd.read_csv(ROOT / "data/processed/case_mix_911.csv")
    cm = cm[cm.share >= 0.002].copy()                         # very rare types fold into their siblings
    cm["share"] = cm.n / cm.n.sum()
    cm[["type", "priority", "need", "share"]].rename(columns={"type": "category", "priority": "severity"}).round({"share": 5}).to_csv(city / "case_mix.csv", index=False)
    print(f"{len(share)} demand zones, {weekly:.0f} EMS calls/week expected for population {a.population:,}; case types {len(cm)}")
    print("top zones by share:", share.sort_values(ascending=False).head(5).round(3).to_dict())


if __name__ == "__main__":
    main()
