#!/usr/bin/env bash
# Download the two public raw datasets (not committed: ~20 MB).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data/raw
[[ -f data/raw/andorra.osm.pbf ]] || curl -fL -o data/raw/andorra.osm.pbf https://github.com/graphhopper/graphhopper/raw/master/core/files/andorra.osm.pbf
[[ -f data/raw/911.csv ]] || curl -fL -o data/raw/911.csv https://raw.githubusercontent.com/roheetnarayanan/911-Analysis/master/911.csv
ls -lh data/raw
