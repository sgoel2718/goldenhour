#!/usr/bin/env sh
# ML sidecar (triage) on :8000, dispatcher + dashboard on $PORT. If the sidecar dies, the dispatcher's
# circuit breaker falls back to rule-based triage — dispatch never stops.
set -e
cd /app
python3 -m uvicorn ml.service.app:app --host 127.0.0.1 --port 8000 --log-level warning &
exec java -jar goldenhour.jar serve
