# ---- build + test the Java core (tests run against the real road graph) ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY core core
COPY data/city data/city
RUN core/build.sh

# ---- runtime: Java dispatcher + Python ML sidecar in one small image ----
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY ml/requirements-serve.txt ml/requirements-serve.txt
RUN pip3 install --no-cache-dir -r ml/requirements-serve.txt
COPY --from=build /app/core/out/goldenhour.jar goldenhour.jar
COPY data/city data/city
COPY web web
COPY reports reports
COPY ml/service ml/service
COPY ml/triage ml/triage
COPY deploy/start.sh start.sh
RUN useradd -m -u 1000 app && mkdir -p data/wal && chown -R app /app
USER app
ENV PORT=7860 GH_ML_URL=http://127.0.0.1:8000 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
EXPOSE 7860
HEALTHCHECK CMD curl -fs http://127.0.0.1:${PORT}/health || exit 1
CMD ["./start.sh"]
