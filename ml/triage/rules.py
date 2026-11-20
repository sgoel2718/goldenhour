"""Deterministic triage rules — the always-available fallback (mirrors com.goldenhour.triage.Triage in Java).

Ordered most-critical first; the first matching rule wins. Under-triage (calling a cardiac arrest "urgent")
is far more dangerous than over-triage, so ambiguous wording errs towards the higher priority.
"""
from __future__ import annotations

RULES = [
    (("not breathing", "unconscious", "unresponsive", "cardiac arrest", "no pulse", "choking"), "P1_CRITICAL", "CARDIAC", "CARDIAC/RESPIRATORY ARREST"),
    (("chest pain", "heart attack", "cardiac"), "P1_CRITICAL", "CARDIAC", "CARDIAC EMERGENCY"),
    (("stroke", "face drooping", "slurred speech", "can't move arm", "cva"), "P1_CRITICAL", "STROKE", "STROKE"),
    (("severe bleeding", "hemorrhag", "haemorrhag", "gunshot", "stab"), "P1_CRITICAL", "TRAUMA", "SEVERE TRAUMA"),
    (("difficulty breathing", "shortness of breath", "asthma", "respiratory"), "P2_EMERGENT", "GENERAL", "RESPIRATORY EMERGENCY"),
    (("burn", "fire", "smoke"), "P2_EMERGENT", "BURNS", "BURNS"),
    (("seizure", "convuls", "overdose", "poison", "diabetic", "allergic", "anaphyla"), "P2_EMERGENT", "GENERAL", "MEDICAL EMERGENCY"),
    (("labour", "labor", "pregnan", "contractions"), "P2_EMERGENT", "MATERNITY", "MATERNITY"),
    (("accident", "collision", "crash", "vehicle", "fall", "fracture", "injur"), "P3_URGENT", "TRAUMA", "INJURY"),
    (("fever", "vomit", "abdominal", "headache", "dizz", "weak", "sick"), "P3_URGENT", "GENERAL", "ILLNESS"),
]
SEVERITIES = ["P1_CRITICAL", "P2_EMERGENT", "P3_URGENT", "P4_ROUTINE"]
SPECIALTIES = ["CARDIAC", "STROKE", "TRAUMA", "BURNS", "MATERNITY", "GENERAL"]


def classify(notes: str) -> dict:
    t = (notes or "").lower()
    for words, sev, need, cat in RULES:
        if any(w in t for w in words):
            return {"severity": sev, "need": need, "category": cat, "model": "rules-v1"}
    return {"severity": "P3_URGENT", "need": "GENERAL", "category": "UNSPECIFIED", "model": "rules-v1"}
