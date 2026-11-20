#!/usr/bin/env bash
# Rebuild every number in the README from raw public data.
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/fetch_data.sh
pip install -q -r ml/requirements.txt osmium
python ml/data_prep/build_road_graph.py --pbf data/raw/andorra.osm.pbf --out data/city     # OSM → routable graph
(cd ml/forecast && python pipeline.py && python spatial_stability.py && python transfer.py)  # 911 data → forecast + demand
python ml/triage/evaluate.py                                                                  # triage accuracy
core/build.sh                                                                                 # 24 Java tests + jar
java -jar core/out/goldenhour.jar simulate data/city 8 3 12 10                               # policy experiments
java -jar core/out/goldenhour.jar bench                                                       # routing benchmarks
