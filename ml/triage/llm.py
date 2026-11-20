"""LLM triage: reads messy, multilingual call-taker notes that keyword rules miss
("my dad is grey and sweaty and says his jaw hurts" is a heart attack with no keyword in sight).

Safety design
  * The model must answer through a strict tool schema (enums only) — free text never reaches the dispatcher.
  * The API key comes from the ANTHROPIC_API_KEY environment variable only; nothing is hard-coded.
  * Any error, timeout or invalid output → the caller falls back to the deterministic rules.
  * The Java dispatcher additionally wraps this whole service in a circuit breaker with a 400 ms budget.
"""
from __future__ import annotations

import os

from .rules import SEVERITIES, SPECIALTIES

MODEL = os.environ.get("GH_TRIAGE_MODEL", "claude-haiku-5-5")
SYSTEM = (
    "You are an emergency medical dispatch triage assistant. Classify the caller's situation.\n"
    "P1_CRITICAL: immediately life-threatening (cardiac arrest, not breathing, chest pain suggesting heart attack, stroke signs, "
    "severe bleeding, unconscious). P2_EMERGENT: serious, could deteriorate (breathing difficulty, seizure, overdose, labour, burns, "
    "major trauma). P3_URGENT: needs care soon but stable (falls, fractures, moderate pain, fever). P4_ROUTINE: non-urgent.\n"
    "When in doubt between two levels choose the HIGHER priority: under-triage costs lives. Notes may be in any language."
)
TOOL = {
    "name": "triage",
    "description": "Record the triage decision.",
    "input_schema": {
        "type": "object",
        "properties": {
            "severity": {"type": "string", "enum": SEVERITIES},
            "need": {"type": "string", "enum": SPECIALTIES, "description": "hospital capability the patient needs"},
            "category": {"type": "string", "description": "short label, e.g. STROKE, CARDIAC EMERGENCY"},
        },
        "required": ["severity", "need", "category"],
    },
}


def available() -> bool:
    return bool(os.environ.get("ANTHROPIC_API_KEY"))


def classify(notes: str, timeout: float = 3.0) -> dict:
    import anthropic

    client = anthropic.Anthropic(timeout=timeout, max_retries=0)
    msg = client.messages.create(model=MODEL, max_tokens=200, system=SYSTEM, tools=[TOOL], tool_choice={"type": "tool", "name": "triage"},
                                 messages=[{"role": "user", "content": notes[:1000]}])
    out = next(b.input for b in msg.content if b.type == "tool_use")
    if out.get("severity") not in SEVERITIES or out.get("need") not in SPECIALTIES:
        raise ValueError(f"invalid triage output: {out}")
    return {"severity": out["severity"], "need": out["need"], "category": str(out.get("category", "UNSPECIFIED"))[:60].upper(), "model": MODEL}
