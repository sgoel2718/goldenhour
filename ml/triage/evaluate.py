"""Evaluate triage on a labelled set of realistic call notes.

The metric that matters is P1 recall (under-triage of critical calls): a missed cardiac arrest is not
comparable to an over-cautious response to a sprained ankle. We report accuracy, P1 recall and the
under-triage rate (true priority higher than predicted) for the rules, and for the LLM when a key is set.
"""
import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from triage import llm, rules  # noqa: E402

ORDER = {s: i for i, s in enumerate(rules.SEVERITIES)}


def score(name, predict, rows):
    preds = [predict(r["notes"])["severity"] for r in rows]
    truth = [r["severity"] for r in rows]
    p1 = [p for p, t in zip(preds, truth) if t == "P1_CRITICAL"]
    res = {"model": name, "n": len(rows), "accuracy": round(sum(p == t for p, t in zip(preds, truth)) / len(rows), 3),
           "p1_recall": round(sum(p == "P1_CRITICAL" for p in p1) / len(p1), 3),
           "under_triage_rate": round(sum(ORDER[p] > ORDER[t] for p, t in zip(preds, truth)) / len(rows), 3),
           "misses": [r["notes"] for r, p in zip(rows, preds) if ORDER[p] > ORDER[r["severity"]]]}
    print(json.dumps(res, indent=2))
    return res


if __name__ == "__main__":
    rows = list(csv.DictReader(open(Path(__file__).with_name("eval_notes.csv"))))
    out = [score("rules-v1", rules.classify, rows)]
    if llm.available():
        out.append(score(llm.MODEL, llm.classify, rows))
    else:
        print("ANTHROPIC_API_KEY not set — LLM triage not evaluated (set it to compare).")
    root = Path(__file__).resolve().parents[2]
    (root / "reports").mkdir(exist_ok=True)
    (root / "reports/triage_eval.json").write_text(json.dumps(out, indent=2))
