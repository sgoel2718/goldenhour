"""Turn downloaded city road networks (data/<slug>/) into browser bundles (web/data/<slug>/city.json.gz).

For each city:
  1. hospitals.csv: kept if curated by hand (Chennai); otherwise up to 14 major hospitals are picked from the
     OpenStreetMap facilities, preferring well-known/government/teaching hospitals, spread across the city and
     each within 150 m of a mapped road. Capabilities of auto-picked hospitals are ASSUMED (flagged in the data).
  2. demand.csv / case_mix.csv: ml/forecast/transfer.py with a population estimate for the covered area.
  3. Java `export-static`: road graph + contraction hierarchy + forecast-optimised ambulance bases.
  4. gzip (the browser inflates it with DecompressionStream), and web/data/cities.json lists every city.

    python scripts/build_city_bundle.py            # every data/<slug> that has nodes.csv
    python scripts/build_city_bundle.py pune
"""
import csv
import gzip
import json
import math
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from download_cities import CITIES  # noqa: E402

# people per km² in the dense central area each extract covers (rough census-based estimates; stated as assumptions)
DENSITY = {"chennai": 17000, "bengaluru": 13000, "mumbai": 25000, "delhi": 13000, "hyderabad": 12000, "kolkata": 22000, "pune": 11000}
AMBULANCES_PER_100K = 1.0          # fleet sizing for the demo (EMS norm discussions in India range ~1 per 50k–100k)
# Hospitals that run 24x7 emergency departments in these cities: government general / teaching hospitals and large chains.
MAJOR = re.compile(r"government|govt|medical college|aiims|general hospital|apollo hospital|fortis|manipal|max (super|hospital|smart)|narayana|\bkims\b|"
                   r"yashoda|care hospital|ruby hall|jaslok|lilavati|hinduja|kokilaben|nanavati|breach candy|sassoon|jehangir|sskm|\bnrs\b|"
                   r"\bmedica\b|\bamri\b|peerless|victoria|bowring|st\.? john|safdarjung|ram manohar|ganga ram|osmania|\bnims\b|nizam|gandhi hospital|"
                   r"\bkem\b|\bsion\b|\bnair\b|cooper|bombay hospital|hindu rao|lok nayak|batra|moolchand|sakra|columbia asia|\baster\b|rainbow|continental|"
                   r"sri ramachandra|\bkauvery\b|\bmiot\b|vijaya|\bsims\b|srm institutes|stanley|rajiv gandhi", re.I)
# not emergency hospitals (single-speciality or outpatient)
SKIP = re.compile(r"eye|dental|dent|skin|ayur|siddha|homeo|unani|vet|animal|ivf|fertility|hair|cosmetic|physio|diagnostic|scan|lab|"
                  r"nursing home|clinic|cancer|onco|urolog|diabet|psychiat|mental|rehab|dialysis|ent |e\.n\.t|ortho clinic|cosmetic", re.I)


def capabilities(name, major):
    n = name.lower()
    if re.search(r"maternity|women|child|children|obstet|gynae|gyne|paediatric|pediatric|kasturba", n):
        return "MATERNITY|GENERAL"
    if re.search(r"government|govt|medical college|aiims|general hospital|safdarjung|ram manohar|lok nayak|osmania|gandhi hospital|\bkem\b|sassoon|sskm|\bnrs\b|victoria|bowring|stanley|rajiv gandhi", n):
        return "CARDIAC|STROKE|TRAUMA|BURNS|MATERNITY|GENERAL"
    if re.search(r"peripheral|taluk|primary health|urban health|dispensary", n):
        return "GENERAL|TRAUMA|MATERNITY"
    if re.search(r"heart|cardi", n):
        return "CARDIAC|GENERAL"
    if re.search(r"ortho|trauma|accident|bone", n):
        return "TRAUMA|GENERAL"
    return "CARDIAC|STROKE|TRAUMA|GENERAL" if major else "GENERAL|TRAUMA"


def haversine(a1, o1, a2, o2):
    p1, p2 = math.radians(a1), math.radians(a2)
    x = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(o2 - o1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(x))


def load_nodes(city):
    with open(city / "nodes.csv", encoding="utf-8") as f:
        return [(float(r["lat"]), float(r["lon"])) for r in csv.DictReader(f)]


def nearest_road(nodes, grid, lat, lon, cell=0.005):
    best = 1e18
    ci, cj = int(lat / cell), int(lon / cell)
    for i in range(ci - 1, ci + 2):
        for j in range(cj - 1, cj + 2):
            for a, o in grid.get((i, j), ()):
                best = min(best, haversine(lat, lon, a, o))
    return best


def pick_hospitals(city):
    nodes = load_nodes(city)
    grid = {}
    for a, o in nodes:
        grid.setdefault((int(a / 0.005), int(o / 0.005)), []).append((a, o))
    cands = []
    with open(city / "facilities.csv", encoding="utf-8") as f:
        for r in csv.reader(f):
            if len(r) < 4 or r[0] != "hospital":
                continue
            name, lat, lon = ",".join(r[1:-2]).strip(), float(r[-2]), float(r[-1])
            if not name or name.lower() in ("hospital", "clinic") or SKIP.search(name):
                continue
            if nearest_road(nodes, grid, lat, lon) > 150:
                continue
            cands.append((2 if MAJOR.search(name) else 1, name, lat, lon))
    cands.sort(key=lambda c: -c[0])
    chosen = []
    for min_gap in (1500, 900, 500):              # spread out: prefer hospitals far from those already chosen
        for c in cands:
            if len(chosen) >= 14:
                break
            if c in chosen or any(x[1].lower() == c[1].lower() for x in chosen):
                continue
            if all(haversine(c[2], c[3], x[2], x[3]) >= min_gap for x in chosen):
                chosen.append(c)
    rows = [{"name": name.replace(",", " ")[:60], "lat": f"{lat:.7f}", "lon": f"{lon:.7f}", "specialties": capabilities(name, score == 2), "phone": ""}
            for score, name, lat, lon in chosen]
    with open(city / "hospitals.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["name", "lat", "lon", "specialties", "phone"], lineterminator="\n")
        w.writeheader(); w.writerows(rows)
    (city / "hospitals_assumed.txt").write_text("Hospital list auto-selected from OpenStreetMap; capabilities are assumptions for the demo.\n")
    return len(rows)


def build(slug):
    city = ROOT / "data" / slug
    name, lat, lon, dist = CITIES[slug]
    if not (city / "hospitals.csv").exists():
        print(f"  picked {pick_hospitals(city)} hospitals from OpenStreetMap")
    area_km2 = (2 * dist / 1000) ** 2
    population = int(area_km2 * DENSITY.get(slug, 12000))
    ambulances = max(8, round(population / 100_000 * AMBULANCES_PER_100K))
    subprocess.run([sys.executable, "transfer.py", "--city", str(city), "--population", str(population)], cwd=ROOT / "ml/forecast", check=True)
    out = ROOT / "web/data" / slug / "city.json"
    subprocess.run(["java", "-jar", str(ROOT / "core/out/goldenhour.jar"), "export-static", str(city), str(out), str(ambulances), "0.35"], cwd=ROOT, check=True)
    raw = out.read_bytes()
    gz = gzip.compress(raw, 9)
    (out.parent / "city.json.gz").write_bytes(gz)
    out.unlink()                                           # ship only the compressed bundle
    nodes = load_nodes(city)
    lats, lons = [a for a, _ in nodes], [o for _, o in nodes]
    return {"slug": slug, "name": name, "center": [lat, lon], "bounds": [[min(lats), min(lons)], [max(lats), max(lons)]],
            "population": population, "ambulances": ambulances, "mb": round(len(gz) / 1e6, 1)}


if __name__ == "__main__":
    slugs = sys.argv[1:] or [p.name for p in sorted((ROOT / "data").iterdir()) if (p / "nodes.csv").exists() and p.name in CITIES]
    index_path = ROOT / "web/data/cities.json"
    index = {c["slug"]: c for c in json.loads(index_path.read_text())} if index_path.exists() else {}
    for slug in slugs:
        print(f"=== {slug}")
        index[slug] = build(slug)
        print(f"  {index[slug]}")
    order = list(CITIES)
    index_path.write_text(json.dumps(sorted(index.values(), key=lambda c: order.index(c["slug"])), indent=1))
    print("cities:", [c["slug"] for c in json.loads(index_path.read_text())])
