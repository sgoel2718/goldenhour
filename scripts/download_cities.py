"""Download real OpenStreetMap road networks for several Indian cities (run this on a machine with internet).

    pip install osmnx
    python scripts/download_cities.py            # all cities (~15-25 min)
    python scripts/download_cities.py pune delhi # just some

Each city lands in data/<slug>/ (nodes.csv, edges.csv, facilities.csv, name.txt). Everything after that
(hospital selection, demand model, ambulance bases, browser bundle) is done by scripts/build_city_bundle.py.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# slug: (display name, centre lat, centre lon, half-width in metres)
CITIES = {
    "chennai":   ("Chennai",   13.0500, 80.2320, 7500),
    "bengaluru": ("Bengaluru", 12.9716, 77.5946, 7000),
    "mumbai":    ("Mumbai",    19.0600, 72.8700, 7000),
    "delhi":     ("Delhi",     28.6139, 77.2090, 7000),
    "hyderabad": ("Hyderabad", 17.3850, 78.4867, 7000),
    "kolkata":   ("Kolkata",   22.5726, 88.3639, 6500),
    "pune":      ("Pune",      18.5204, 73.8567, 6500),
}

if __name__ == "__main__":
    wanted = sys.argv[1:] or list(CITIES)
    for slug in wanted:
        name, lat, lon, dist = CITIES[slug]
        out = ROOT / "data" / slug
        print(f"\n=== {name}: {dist / 1000:.1f} km around {lat},{lon} ===", flush=True)
        subprocess.run([sys.executable, str(ROOT / "ml/data_prep/build_road_graph.py"), "--point", f"{lat},{lon}",
                        "--dist", str(dist), "--speed-factor", "0.6", "--out", str(out)], check=True)
        (out / "name.txt").write_text(f"{name}\nAsia/Kolkata\n", encoding="utf-8")
    print("\nAll done. Reply 'done' (or run scripts/build_city_bundle.py yourself).")
