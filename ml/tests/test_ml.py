import sys
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "forecast"))
from forecast.pipeline import geohash, poisson_deviance  # noqa: E402
from service.app import app  # noqa: E402
from triage import rules  # noqa: E402

client = TestClient(app)


def test_geohash_known_values():
    assert geohash(57.64911, 10.40744, 11) == "u4pruydqqvj"      # reference example from geohash.org
    assert geohash(42.5063, 1.5218, 5) == "sp91f"


def test_poisson_deviance_zero_for_perfect_and_handles_zero_counts():
    y = np.array([0, 1, 3, 0])
    assert abs(poisson_deviance(y, y.astype(float) + 1e-12)) < 1e-6
    assert poisson_deviance(y, np.full(4, 1.0)) > 0


def test_rules_priorities():
    assert rules.classify("Man NOT BREATHING")["severity"] == "P1_CRITICAL"
    assert rules.classify("slurred speech")["need"] == "STROKE"
    assert rules.classify("")["severity"] == "P3_URGENT"


def test_triage_endpoint_falls_back_to_rules_without_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    r = client.post("/triage", json={"notes": "chest pain and sweating"})
    assert r.status_code == 200
    assert r.json()["severity"] == "P1_CRITICAL" and r.json()["model"] == "rules-v1"


def test_llm_failure_falls_back(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-not-a-real-key")
    from triage import llm
    monkeypatch.setattr(llm, "classify", lambda notes, timeout=3.0: (_ for _ in ()).throw(TimeoutError("slow")))
    r = client.post("/triage", json={"notes": "fell off a ladder"})
    assert r.json()["model"] == "rules-v1"


def test_validation():
    assert client.post("/triage", json={"notes": "x" * 2000}).status_code == 422


def test_forecast_beats_baselines():
    r = client.get("/forecast/metrics").json()
    m = r["metrics"]
    assert m["lightgbm_poisson"]["poisson_deviance"] < m["historical_avg (zone x hour-of-week)"]["poisson_deviance"]
