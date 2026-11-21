"""Download real OpenStreetMap road networks for the prebuilt cities (run on a machine with internet).

    pip install osmnx
    python scripts/download_cities.py                 # every city that is not downloaded yet (resumable; ~1-3 min each)
    python scripts/download_cities.py pune delhi      # just some
    python scripts/download_cities.py --india         # only the Indian cities

Each city lands in data/<slug>/ (nodes.csv, edges.csv, facilities.csv, name.txt). Everything after that
(hospital selection, demand model, ambulance bases, contraction hierarchy, browser bundle) is done by
scripts/build_city_bundle.py. Places that are not in this list are built live in the browser instead.
"""
import subprocess
import sys
from collections import namedtuple
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# name, centre lat/lon, half-width (m), ISO country, time zone, people per km² in the covered core (rough, stated assumption)
City = namedtuple("City", "name lat lon dist cc tz density")
CITIES = {
    "chennai": City("Chennai", 13.0500, 80.2320, 7500, "in", "Asia/Kolkata", 17000),
    "mumbai": City("Mumbai", 19.0600, 72.8700, 7000, "in", "Asia/Kolkata", 25000),
    "delhi": City("Delhi", 28.6139, 77.2090, 7000, "in", "Asia/Kolkata", 13000),
    "bengaluru": City("Bengaluru", 12.9716, 77.5946, 7000, "in", "Asia/Kolkata", 13000),
    "hyderabad": City("Hyderabad", 17.3850, 78.4867, 7000, "in", "Asia/Kolkata", 12000),
    "kolkata": City("Kolkata", 22.5726, 88.3639, 6500, "in", "Asia/Kolkata", 22000),
    "pune": City("Pune", 18.5204, 73.8567, 6500, "in", "Asia/Kolkata", 11000),
    "ahmedabad": City("Ahmedabad", 23.0225, 72.5714, 6500, "in", "Asia/Kolkata", 12000),
    "jaipur": City("Jaipur", 26.9124, 75.7873, 6000, "in", "Asia/Kolkata", 9000),
    "lucknow": City("Lucknow", 26.8467, 80.9462, 6000, "in", "Asia/Kolkata", 9000),
    "surat": City("Surat", 21.1702, 72.8311, 6000, "in", "Asia/Kolkata", 12000),
    "kanpur": City("Kanpur", 26.4499, 80.3319, 5500, "in", "Asia/Kolkata", 10000),
    "nagpur": City("Nagpur", 21.1458, 79.0882, 5500, "in", "Asia/Kolkata", 9000),
    "indore": City("Indore", 22.7196, 75.8577, 5500, "in", "Asia/Kolkata", 9000),
    "bhopal": City("Bhopal", 23.2599, 77.4126, 5500, "in", "Asia/Kolkata", 7000),
    "patna": City("Patna", 25.5941, 85.1376, 5500, "in", "Asia/Kolkata", 10000),
    "vadodara": City("Vadodara", 22.3072, 73.1812, 5000, "in", "Asia/Kolkata", 9000),
    "ludhiana": City("Ludhiana", 30.9010, 75.8573, 5000, "in", "Asia/Kolkata", 9000),
    "agra": City("Agra", 27.1767, 78.0081, 5000, "in", "Asia/Kolkata", 9000),
    "nashik": City("Nashik", 19.9975, 73.7898, 5000, "in", "Asia/Kolkata", 8000),
    "varanasi": City("Varanasi", 25.3176, 82.9739, 5000, "in", "Asia/Kolkata", 10000),
    "prayagraj": City("Prayagraj", 25.4358, 81.8463, 5000, "in", "Asia/Kolkata", 9000),
    "coimbatore": City("Coimbatore", 11.0168, 76.9558, 5000, "in", "Asia/Kolkata", 8000),
    "madurai": City("Madurai", 9.9252, 78.1198, 5000, "in", "Asia/Kolkata", 9000),
    "tiruchirappalli": City("Tiruchirappalli", 10.7905, 78.7047, 5000, "in", "Asia/Kolkata", 7000),
    "kochi": City("Kochi", 9.9312, 76.2673, 5000, "in", "Asia/Kolkata", 7000),
    "thiruvananthapuram": City("Thiruvananthapuram", 8.5241, 76.9366, 5000, "in", "Asia/Kolkata", 6000),
    "visakhapatnam": City("Visakhapatnam", 17.6868, 83.2185, 5000, "in", "Asia/Kolkata", 7000),
    "vijayawada": City("Vijayawada", 16.5062, 80.6480, 5000, "in", "Asia/Kolkata", 9000),
    "guwahati": City("Guwahati", 26.1445, 91.7362, 5000, "in", "Asia/Kolkata", 6000),
    "bhubaneswar": City("Bhubaneswar", 20.2961, 85.8245, 5000, "in", "Asia/Kolkata", 6000),
    "chandigarh": City("Chandigarh", 30.7333, 76.7794, 5000, "in", "Asia/Kolkata", 8000),
    "dehradun": City("Dehradun", 30.3165, 78.0322, 5000, "in", "Asia/Kolkata", 5000),
    "ranchi": City("Ranchi", 23.3441, 85.3096, 5000, "in", "Asia/Kolkata", 6000),
    "raipur": City("Raipur", 21.2514, 81.6296, 5000, "in", "Asia/Kolkata", 6000),
    "amritsar": City("Amritsar", 31.6340, 74.8723, 5000, "in", "Asia/Kolkata", 9000),
    "mysuru": City("Mysuru", 12.2958, 76.6394, 5000, "in", "Asia/Kolkata", 6000),
    "mangaluru": City("Mangaluru", 12.9141, 74.8560, 5000, "in", "Asia/Kolkata", 5000),
    "jodhpur": City("Jodhpur", 26.2389, 73.0243, 5000, "in", "Asia/Kolkata", 7000),
    "gwalior": City("Gwalior", 26.2183, 78.1828, 5000, "in", "Asia/Kolkata", 7000),
    "jabalpur": City("Jabalpur", 23.1815, 79.9864, 5000, "in", "Asia/Kolkata", 7000),
    "rajkot": City("Rajkot", 22.3039, 70.8022, 5000, "in", "Asia/Kolkata", 8000),
    "meerut": City("Meerut", 28.9845, 77.7064, 5000, "in", "Asia/Kolkata", 8000),
    "srinagar": City("Srinagar", 34.0837, 74.7973, 5000, "in", "Asia/Kolkata", 6000),
    "panaji": City("Panaji (Goa)", 15.4909, 73.8278, 5000, "in", "Asia/Kolkata", 3000),
    "noida": City("Noida", 28.5355, 77.3910, 5000, "in", "Asia/Kolkata", 8000),
    "gurugram": City("Gurugram", 28.4595, 77.0266, 5000, "in", "Asia/Kolkata", 6000),
    "thane": City("Thane", 19.2183, 72.9781, 5000, "in", "Asia/Kolkata", 14000),
    "navi-mumbai": City("Navi Mumbai", 19.0330, 73.0297, 5000, "in", "Asia/Kolkata", 8000),
    "london": City("London", 51.5074, -0.1278, 5000, "gb", "Europe/London", 11000),
    "new-york": City("New York", 40.7580, -73.9855, 5000, "us", "America/New_York", 25000),
    "los-angeles": City("Los Angeles", 34.0522, -118.2437, 5000, "us", "America/Los_Angeles", 6000),
    "chicago": City("Chicago", 41.8781, -87.6298, 5000, "us", "America/Chicago", 8000),
    "toronto": City("Toronto", 43.6532, -79.3832, 5000, "ca", "America/Toronto", 7000),
    "paris": City("Paris", 48.8566, 2.3522, 5000, "fr", "Europe/Paris", 20000),
    "berlin": City("Berlin", 52.5200, 13.4050, 5000, "de", "Europe/Berlin", 8000),
    "madrid": City("Madrid", 40.4168, -3.7038, 5000, "es", "Europe/Madrid", 12000),
    "rome": City("Rome", 41.9028, 12.4964, 5000, "it", "Europe/Rome", 8000),
    "amsterdam": City("Amsterdam", 52.3676, 4.9041, 5000, "nl", "Europe/Amsterdam", 8000),
    "istanbul": City("Istanbul", 41.0082, 28.9784, 5000, "tr", "Europe/Istanbul", 14000),
    "moscow": City("Moscow", 55.7558, 37.6173, 5000, "ru", "Europe/Moscow", 10000),
    "dubai": City("Dubai", 25.2048, 55.2708, 5000, "ae", "Asia/Dubai", 5000),
    "riyadh": City("Riyadh", 24.7136, 46.6753, 5000, "sa", "Asia/Riyadh", 5000),
    "cairo": City("Cairo", 30.0444, 31.2357, 5000, "eg", "Africa/Cairo", 20000),
    "lagos": City("Lagos", 6.5244, 3.3792, 5000, "ng", "Africa/Lagos", 15000),
    "nairobi": City("Nairobi", -1.2864, 36.8172, 5000, "ke", "Africa/Nairobi", 6000),
    "johannesburg": City("Johannesburg", -26.2041, 28.0473, 5000, "za", "Africa/Johannesburg", 4000),
    "karachi": City("Karachi", 24.8607, 67.0011, 5000, "pk", "Asia/Karachi", 15000),
    "lahore": City("Lahore", 31.5204, 74.3587, 5000, "pk", "Asia/Karachi", 12000),
    "dhaka": City("Dhaka", 23.8103, 90.4125, 5000, "bd", "Asia/Dhaka", 30000),
    "colombo": City("Colombo", 6.9271, 79.8612, 5000, "lk", "Asia/Colombo", 8000),
    "kathmandu": City("Kathmandu", 27.7172, 85.3240, 5000, "np", "Asia/Kathmandu", 10000),
    "singapore": City("Singapore", 1.2903, 103.8519, 5000, "sg", "Asia/Singapore", 8000),
    "kuala-lumpur": City("Kuala Lumpur", 3.1390, 101.6869, 5000, "my", "Asia/Kuala_Lumpur", 7000),
    "bangkok": City("Bangkok", 13.7563, 100.5018, 5000, "th", "Asia/Bangkok", 10000),
    "jakarta": City("Jakarta", -6.2088, 106.8456, 5000, "id", "Asia/Jakarta", 15000),
    "manila": City("Manila", 14.5995, 120.9842, 5000, "ph", "Asia/Manila", 25000),
    "hong-kong": City("Hong Kong", 22.3193, 114.1694, 5000, "hk", "Asia/Hong_Kong", 20000),
    "shanghai": City("Shanghai", 31.2304, 121.4737, 5000, "cn", "Asia/Shanghai", 15000),
    "beijing": City("Beijing", 39.9042, 116.4074, 5000, "cn", "Asia/Shanghai", 10000),
    "seoul": City("Seoul", 37.5665, 126.9780, 5000, "kr", "Asia/Seoul", 16000),
    "tokyo": City("Tokyo", 35.6812, 139.7671, 5000, "jp", "Asia/Tokyo", 15000),
    "sydney": City("Sydney", -33.8688, 151.2093, 5000, "au", "Australia/Sydney", 5000),
    "sao-paulo": City("São Paulo", -23.5505, -46.6333, 5000, "br", "America/Sao_Paulo", 8000),
    "mexico-city": City("Mexico City", 19.4326, -99.1332, 5000, "mx", "America/Mexico_City", 9000),
}

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    wanted = args or [s for s, c in CITIES.items() if "--india" not in sys.argv or c.cc == "in"]
    failed = []
    for k, slug in enumerate(wanted, 1):
        c, out = CITIES[slug], ROOT / "data" / slug
        if (out / "edges.csv").exists() and not args:
            continue                                     # already downloaded: resume where we left off
        print(f"\n=== [{k}/{len(wanted)}] {c.name}: {c.dist / 1000:.1f} km around {c.lat},{c.lon} ===", flush=True)
        speed = "0.6" if c.cc in ("in", "pk", "bd", "np", "lk", "eg", "ng", "ke", "id", "ph", "th") else "0.75"
        try:
            subprocess.run([sys.executable, str(ROOT / "ml/data_prep/build_road_graph.py"), "--point", f"{c.lat},{c.lon}",
                            "--dist", str(c.dist), "--speed-factor", speed, "--out", str(out)], check=True)
            (out / "name.txt").write_text(f"{c.name}\n{c.tz}\n", encoding="utf-8")
        except subprocess.CalledProcessError:
            failed.append(slug)
    print("\nAll done." + (f" Failed (just run the script again): {failed}" if failed else ""))
