"""
Where SigNoz's alert notifications land, so that "the alert fired" is evidence
rather than a screenshot.

WHY THIS EXISTS

SigNoz refuses to create a rule with no notification channel - "at least one
channel is required" - and this stack has no Slack workspace, no PagerDuty
account and no SMTP server. A webhook pointing at nothing would satisfy the
requirement and teach us nothing.

So it points here instead, and the phase-4 exit criterion becomes checkable by
a script:

    docker logs payorch-alert-sink | grep FIRING

That matters more than it looks. SigNoz knows an alert fired, but reading it
back out means either the UI or a query against its internal state. A webhook
is the only part of the alerting path that proves the whole chain worked -
rule evaluated, threshold crossed, notification routed, message delivered. An
alert that evaluates correctly and never notifies anyone is still a failure,
and it is exactly the failure nobody notices until an incident.

WHAT IT WRITES

One line per notification, JSON, on stdout - the same shape everything else in
this project logs, so `docker compose logs` stays readable across services:

    {"received": "...", "status": "firing", "alerts": 1,
     "names": ["breaker-open"], "at": "..."}

Alertmanager posts a batch, so `alerts` is a count and `names` a list. The
`status` is the whole point: SigNoz sends "firing" when the condition starts
matching and "resolved" when it stops, which is the second half of the exit
criterion and the half that is easy to forget to check.

No dependencies. stdlib http.server on purpose: this is a test double, and a
test double that needs its own dependency management is a liability.
"""

import json
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 9095


def emit(payload):
    """One JSON line on stdout, flushed.

    Flushed explicitly because stdout is a pipe to Docker, not a tty, so
    Python block-buffers it. Without the flush the lines appear in
    `docker logs` minutes late or, if the container is stopped first, never -
    and the evidence for an experiment would depend on how it was shut down.
    """
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


class Sink(BaseHTTPRequestHandler):

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"

        # Answer FIRST, then log. Alertmanager retries a webhook that does not
        # respond, and a retry would double-count a firing in the evidence.
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

        received = datetime.now(timezone.utc).isoformat()
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            # Never drop a notification just because it did not parse. A
            # malformed body still means something fired, and silently
            # discarding it would make the sink lie by omission.
            emit({"received": received, "status": "unparseable",
                  "raw": raw.decode("utf-8", "replace")[:2000]})
            return

        alerts = body.get("alerts", [])
        emit({
            "received": received,
            "status": body.get("status", "unknown"),
            "alerts": len(alerts),
            # alertname is the rule name. Grouped notifications can carry
            # several, which is why this is a list even when it is usually one.
            "names": sorted({a.get("labels", {}).get("alertname", "?") for a in alerts}),
            "severities": sorted({a.get("labels", {}).get("severity", "?") for a in alerts}),
            # Per-alert start/end, so a run can be reconstructed after the fact
            # without correlating against the load test's own clock.
            "startsAt": [a.get("startsAt") for a in alerts],
            "endsAt": [a.get("endsAt") for a in alerts],
        })

    def do_GET(self):
        # A health endpoint, so compose can tell "started" from "listening".
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"alert-sink\n")

    def log_message(self, *args):
        # Silence the default access log. Every POST already produces exactly
        # one structured line above, and the stock one-line-per-request format
        # would double every entry in the evidence.
        pass


if __name__ == "__main__":
    emit({"received": datetime.now(timezone.utc).isoformat(),
          "status": "listening", "port": PORT})
    HTTPServer(("0.0.0.0", PORT), Sink).serve_forever()
