#!/usr/bin/env bash
# Build the serverless edition into web/: road graph + CH (preprocessed by the Java core) + reports.
# The web/ folder can then be hosted on any static host (Hugging Face static Space, GitHub Pages, Netlify).
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f core/out/goldenhour.jar ]] || core/build.sh --skip-tests
java -jar core/out/goldenhour.jar export-static data/chennai web/data/city.json 20 0.35
cp reports/simulation.md reports/benchmarks.md reports/forecast_metrics.json web/data/
ls -lh web/data
