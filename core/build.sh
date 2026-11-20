#!/usr/bin/env bash
# Build + test the Java core with nothing but a JDK 21 (no Maven/Gradle download needed).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/main out/test
javac --release 21 -d out/main $(find src/main -name "*.java")
javac --release 21 -cp out/main -d out/test $(find src/test -name "*.java")
if [[ "${1:-}" != "--skip-tests" ]]; then java -cp out/main:out/test -Dgh.data=../data/city com.goldenhour.Check; fi
jar --create --file out/goldenhour.jar --main-class com.goldenhour.Main -C out/main .
echo "built out/goldenhour.jar"
