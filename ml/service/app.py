"""GoldenHour ML service (FastAPI).

  POST /triage            {"notes": "..."} → severity / needed specialty / category (LLM when configured, rules otherwise)
  GET  /forecast/metrics  holdout evaluation of the demand-forecasting model
  GET  /health

The Java dispatcher calls /triage with a 400 ms budget behind a circuit breaker, so this service can be slow,
down or rate-limited without ever blocking an ambulance dispatch.
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel, Field

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from triage import llm, rules  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
app = FastAPI(title="GoldenHour ML", version="1.0")
stats = {"llm_calls": 0, "llm_failures": 0, "rules_calls": 0}


class TriageIn(BaseModel):
    notes: str = Field(..., max_length=1000)


class TriageOut(BaseModel):
    severity: str
    need: str
    category: str
    model: str
    ms: float


@app.get("/health")
def health():
    return {"status": "ok", "llm": llm.available(), **stats}


@app.post("/triage", response_model=TriageOut)
def triage(body: TriageIn):
    t0 = time.perf_counter()
    if llm.available():
        try:
            stats["llm_calls"] += 1
            return {**llm.classify(body.notes), "ms": (time.perf_counter() - t0) * 1000}
        except Exception:                      # timeouts, rate limits, invalid output → rules
            stats["llm_failures"] += 1
    stats["rules_calls"] += 1
    return {**rules.classify(body.notes), "ms": (time.perf_counter() - t0) * 1000}


@app.get("/forecast/metrics")
def forecast_metrics():
    p = ROOT / "reports/forecast_metrics.json"
    return json.loads(p.read_text()) if p.exists() else {"error": "run ml/forecast/pipeline.py first"}
