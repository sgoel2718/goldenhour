"""Build a routable, drivable road graph from an OpenStreetMap extract.

Output (consumed by the Java dispatch core):
  nodes.csv  id,lat,lon                (dense ids 0..n-1)
  edges.csv  from,to,length_m,speed_kph,travel_s,road_class

Usage:
  python build_road_graph.py --pbf data/raw/andorra.osm.pbf --out data/city      # any .osm.pbf extract (e.g. Geofabrik)
  python build_road_graph.py --place "Chennai, India" --out data/chennai          # needs internet + `pip install osmnx`
  python build_road_graph.py --point 13.05,80.24 --dist 6000 --speed-factor 0.6 --out data/chennai

Design notes
  * Only drivable highway classes are kept; `oneway` is respected (ambulances obey one-way streets
    except where a city grants exemptions — configurable later as an edge flag).
  * Speeds come from `maxspeed` when present, otherwise a per-class default (urban emergency-vehicle speeds).
  * We keep the largest strongly connected component so every node can reach every other node —
    otherwise a dispatcher could pick an ambulance that cannot physically reach the incident.
"""
from __future__ import annotations

import argparse
import math
import os
from collections import defaultdict

DEFAULT_SPEED_KPH = {
    "motorway": 90, "trunk": 70, "primary": 55, "secondary": 45, "tertiary": 40, "unclassified": 30,
    "residential": 25, "living_street": 10, "service": 15, "motorway_link": 50, "trunk_link": 40,
    "primary_link": 40, "secondary_link": 35, "tertiary_link": 30, "road": 30,
}
DRIVABLE = set(DEFAULT_SPEED_KPH)


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def parse_speed(tag: str | None, road_class: str) -> float:
    if tag:
        try:
            v = float(tag.split()[0].replace("mph", ""))
            return v * 1.609 if "mph" in tag else v
        except ValueError:
            pass
    return float(DEFAULT_SPEED_KPH.get(road_class, 30))


def from_pbf(path: str):
    import osmium

    coords, ways = {}, []
    facilities = []          # hospitals, clinics, fire/ambulance stations (real OSM features)

    def facility(tags, lat, lon):
        kind = tags.get("amenity") or tags.get("emergency")
        if kind in ("hospital", "clinic", "fire_station", "ambulance_station"):
            facilities.append((kind, (tags.get("name") or kind).replace(",", " "), lat, lon))

    class Handler(osmium.SimpleHandler):
        def node(self, n):
            if n.tags and n.location.valid():
                facility(n.tags, n.location.lat, n.location.lon)

        def way(self, w):
            if w.tags.get("amenity") or w.tags.get("emergency"):
                pts = [(n.location.lat, n.location.lon) for n in w.nodes if n.location.valid()]
                if pts:
                    facility(w.tags, sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
            hw = w.tags.get("highway")
            if hw not in DRIVABLE or w.tags.get("access") in ("no", "private") or w.tags.get("area") == "yes":
                return
            refs = [n.ref for n in w.nodes]
            for n in w.nodes:
                if n.location.valid():
                    coords[n.ref] = (n.location.lat, n.location.lon)
            ow = w.tags.get("oneway", "no")
            if hw in ("motorway", "motorway_link") or w.tags.get("junction") == "roundabout":
                ow = w.tags.get("oneway", "yes")
            ways.append((refs, hw, w.tags.get("maxspeed"), ow))

    Handler().apply_file(path, locations=True)
    edges = []
    for refs, hw, maxspeed, ow in ways:
        speed = parse_speed(maxspeed, hw)
        for a, b in zip(refs, refs[1:]):
            if a not in coords or b not in coords or a == b:
                continue
            length = haversine_m(*coords[a], *coords[b])
            if ow in ("yes", "true", "1"):
                edges.append((a, b, length, speed, hw))
            elif ow == "-1":
                edges.append((b, a, length, speed, hw))
            else:
                edges.append((a, b, length, speed, hw))
                edges.append((b, a, length, speed, hw))
    return coords, edges, facilities


def from_place(place: str | None, point: tuple | None = None, dist: int = 6000):
    import osmnx as ox

    g = ox.graph_from_point(point, dist=dist, network_type="drive") if point else ox.graph_from_place(place, network_type="drive")
    coords = {n: (d["y"], d["x"]) for n, d in g.nodes(data=True)}
    edges = []
    for u, v, d in g.edges(data=True):
        hw = d.get("highway", "road")
        hw = hw[0] if isinstance(hw, list) else hw
        ms = d.get("maxspeed")
        ms = ms[0] if isinstance(ms, list) else ms
        edges.append((u, v, float(d["length"]), parse_speed(ms, hw), hw))
    facilities = []
    try:
        tags = {"amenity": ["hospital", "clinic", "fire_station"], "emergency": "ambulance_station"}
        feats = ox.features_from_point(point, tags, dist=dist) if point else ox.features_from_place(place, tags)
        for _, f in feats.iterrows():
            c = f.geometry.centroid
            facilities.append((f.get("amenity") or f.get("emergency"), str(f.get("name") or "facility").replace(",", " "), c.y, c.x))
    except Exception as e:  # facilities are optional
        print("no facilities:", e)
    return coords, edges, facilities


def largest_scc(nodes: set, edges):
    """Iterative Tarjan's algorithm — recursion would overflow on a 100k-node road graph."""
    adj = defaultdict(list)
    for a, b, *_ in edges:
        adj[a].append(b)
    index, low, on_stack, stack, comps, counter = {}, {}, set(), [], [], 0
    for root in nodes:
        if root in index:
            continue
        work = [(root, iter(adj[root]))]
        index[root] = low[root] = counter; counter += 1; stack.append(root); on_stack.add(root)
        while work:
            v, it = work[-1]
            advanced = False
            for w in it:
                if w not in index:
                    index[w] = low[w] = counter; counter += 1; stack.append(w); on_stack.add(w)
                    work.append((w, iter(adj[w]))); advanced = True
                    break
                if w in on_stack:
                    low[v] = min(low[v], index[w])
            if advanced:
                continue
            work.pop()
            if work:
                low[work[-1][0]] = min(low[work[-1][0]], low[v])
            if low[v] == index[v]:
                comp = set()
                while True:
                    w = stack.pop(); on_stack.discard(w); comp.add(w)
                    if w == v:
                        break
                comps.append(comp)
    return max(comps, key=len)


def main():
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--pbf"); src.add_argument("--place")
    src.add_argument("--point", help="lat,lon centre, e.g. 13.0500,80.2400 (Chennai)")
    ap.add_argument("--dist", type=int, default=6000, help="radius in metres with --point")
    ap.add_argument("--speed-factor", type=float, default=1.0,
                    help="scale free-flow speeds for congestion, e.g. 0.6 for dense Indian city traffic")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.pbf:
        coords, edges, facilities = from_pbf(a.pbf)
    else:
        pt = tuple(float(x) for x in a.point.split(",")) if a.point else None
        coords, edges, facilities = from_place(a.place, pt, a.dist)
    if a.speed_factor != 1.0:
        edges = [(u, v, length, speed * a.speed_factor, hw) for u, v, length, speed, hw in edges]
    used = {x for e in edges for x in e[:2]}
    keep = largest_scc(used, edges)
    ids = {osm: i for i, osm in enumerate(sorted(keep))}
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "nodes.csv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("id,lat,lon\n")
        for osm, i in ids.items():
            lat, lon = coords[osm]
            f.write(f"{i},{lat:.7f},{lon:.7f}\n")
    best = {}
    for a_, b_, length, speed, hw in edges:
        if a_ in ids and b_ in ids:
            key = (ids[a_], ids[b_])
            t = length / (speed / 3.6)
            if key not in best or t < best[key][2]:
                best[key] = (length, speed, t, hw)
    with open(os.path.join(a.out, "edges.csv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("from,to,length_m,speed_kph,travel_s,road_class\n")
        for (u, v), (length, speed, t, hw) in best.items():
            f.write(f"{u},{v},{length:.1f},{speed:.0f},{t:.2f},{hw}\n")
    with open(os.path.join(a.out, "facilities.csv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("kind,name,lat,lon\n")
        for kind, name, lat, lon in facilities:
            f.write(f"{kind},{name},{lat:.7f},{lon:.7f}\n")
    print(f"facilities: {len(facilities)} (hospitals/clinics/stations)")
    print(f"kept {len(ids):,} of {len(used):,} nodes (largest strongly connected component), {len(best):,} directed edges -> {a.out}")


if __name__ == "__main__":
    main()
