#!/usr/bin/env bash
# Build the serverless edition into web/data/: one compressed bundle per city (road graph + contraction hierarchy
# preprocessed by the Java core + hospitals + ambulance bases + demand) and the Chennai results.
# The web/ folder can then be hosted on any static host (Hugging Face static Space, GitHub Pages, Netlify).
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f core/out/goldenhour.jar ]] || core/build.sh --skip-tests
python3 scripts/build_city_bundle.py "$@"
cp reports/simulation.md reports/benchmarks.md reports/forecast_metrics.json web/data/
du -sh web/data/*/
