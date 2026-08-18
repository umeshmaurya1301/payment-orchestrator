"""
A merchant's webhook endpoint. The other side of phase 6h.

WHY A SEPARATE PROCESS, AND WHY PYTHON

Because the interesting question is not "does our signer agree with our
verifier". Two halves of one codebase using one shared helper will always agree,
including when both are wrong. A merchant integrating with a payment provider
writes their verifier from the documentation, in their own language, and the
signing scheme is only real if that independent implementation accepts it.

So the rules below were written from the header format, not from
WebhookSigner.java, and the experiment passes only if the two agree. That is
also why this file spells out the canonical string it signs rather than calling
a library: it is the specification, executable.

WHAT IT CHECKS, IN ORDER, AND WHY THAT ORDER

    1. a signature header is present at all
    2. it parses as `t=<unix seconds>,v1=<hex>`
    3. the timestamp is within TOLERANCE of now
    4. HMAC-SHA256 over "<t>.<raw body>" with the shared secret matches v1
    5. optionally, the event id has not been seen before

Freshness (3) is checked BEFORE the signature (4) on purpose - it is far cheaper
and it is the check that turns a stolen-but-valid request into a rejected one.
The signature covers the timestamp, so an attacker cannot move it without
invalidating v1; and without the freshness check the signature alone makes a
captured request valid forever.

Check 5 is separate from the signature scheme deliberately, and configurable,
because the experiment needs to measure what timestamp tolerance does and does
NOT prevent. "Timestamp replay protection" bounds the replay window; it does not
close it. Inside the window a captured delivery is a perfectly valid request and
only idempotency on the event id refuses it.

CONFIGURATION

    WEBHOOK_SECRET      shared secret. EMPTY means accept everything, which is
                        the "before" arm of the experiment rather than an
                        oversight.
    WEBHOOK_TOLERANCE   seconds either side of now. Default 300.
    WEBHOOK_DEDUPE      "true" to reject a repeated event id.

The secret is a local development value, in the open, like every other secret in
this repo until phase 9c.

No dependencies. stdlib http.server on purpose: this is a test double, and a
test double that needs its own dependency management is a liability.
"""

import hashlib
import hmac
import json
import os
import sys
import time
from collections import OrderedDict
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Lock

PORT = 9096

SECRET = os.environ.get("WEBHOOK_SECRET", "")
TOLERANCE = int(os.environ.get("WEBHOOK_TOLERANCE", "300"))
DEDUPE = os.environ.get("WEBHOOK_DEDUPE", "false").lower() == "true"

SIGNATURE_HEADER = "X-Payorch-Signature"
EVENT_ID_HEADER = "X-Payorch-Event-Id"

_lock = Lock()
_deliveries = []
_seen = OrderedDict()
_counts = {"accepted": 0, "rejected": 0}
_reasons = {}


def emit(payload):
    """One JSON line on stdout, flushed - see alert-sink for why explicitly."""
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def verify(headers, body):
    """(ok, reason). reason is None when accepted.

    Returns rather than raises so every rejection is counted by cause. An
    experiment that only knows "rejected" cannot tell a working signature check
    from a receiver that is down.
    """
    if not SECRET:
        # The "before" arm. Anything that arrives is accepted, which is what an
        # unsigned webhook endpoint IS - the point of the experiment is what
        # that costs, measured rather than asserted.
        return True, None

    raw = headers.get(SIGNATURE_HEADER)
    if not raw:
        return False, "missing_signature"

    parts = {}
    for piece in raw.split(","):
        key, _, value = piece.strip().partition("=")
        parts[key] = value
    timestamp = parts.get("t")
    provided = parts.get("v1")
    if not timestamp or not provided:
        return False, "malformed_signature"

    try:
        sent_at = int(timestamp)
    except ValueError:
        return False, "malformed_signature"

    if abs(time.time() - sent_at) > TOLERANCE:
        return False, "stale_timestamp"

    # The canonical string. The timestamp is INSIDE the signed material - if it
    # were only a header, an attacker replaying a captured request would simply
    # rewrite it and the freshness check above would be decoration.
    signed = f"{timestamp}.".encode() + body
    expected = hmac.new(SECRET.encode(), signed, hashlib.sha256).hexdigest()

    # compare_digest, not ==. String comparison returns early on the first
    # differing byte, and that timing is enough to recover a signature byte by
    # byte given enough attempts. It is the one line of this file that is about
    # cryptography rather than about plumbing.
    if not hmac.compare_digest(expected, provided):
        return False, "bad_signature"

    if DEDUPE:
        event_id = headers.get(EVENT_ID_HEADER)
        if event_id:
            with _lock:
                if event_id in _seen:
                    return False, "duplicate_event"
                _seen[event_id] = True
                while len(_seen) > 10000:
                    _seen.popitem(last=False)

    return True, None


class Handler(BaseHTTPRequestHandler):

    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)

        ok, reason = verify(self.headers, body)

        try:
            parsed = json.loads(body.decode())
        except Exception:
            parsed = {}
        data = parsed.get("data", {}) if isinstance(parsed, dict) else {}

        record = {
            "at": datetime.now(timezone.utc).isoformat(),
            "accepted": ok,
            "reason": reason,
            "eventId": self.headers.get(EVENT_ID_HEADER),
            "type": parsed.get("type") if isinstance(parsed, dict) else None,
            "paymentId": data.get("paymentId"),
            "amountMinor": data.get("amountMinor"),
            "traceparent": self.headers.get("traceparent"),
            # The raw request, kept so the experiment can REPLAY a genuine
            # delivery rather than construct one it signed itself. Those are
            # different attacks: one needs the wire, the other needs the secret,
            # and only the first is what timestamp tolerance is aimed at.
            "signature": self.headers.get(SIGNATURE_HEADER),
            "body": body.decode("utf-8", "replace"),
        }

        with _lock:
            _deliveries.append(record)
            if ok:
                _counts["accepted"] += 1
            else:
                _counts["rejected"] += 1
                _reasons[reason] = _reasons.get(reason, 0) + 1
            while len(_deliveries) > 5000:
                _deliveries.pop(0)

        emit({"webhook": "accepted" if ok else "rejected", **record})

        if ok:
            self._json(200, {"received": True})
        else:
            # 400, not 401. A merchant rejecting a webhook it cannot verify is
            # saying "this request is wrong", and the sender should NOT keep
            # retrying it - a signature that does not verify will not verify on
            # the fourth attempt either.
            self._json(400, {"received": False, "reason": reason})

    def do_GET(self):
        with _lock:
            payload = {
                "verifying": bool(SECRET),
                "toleranceSeconds": TOLERANCE,
                "dedupe": DEDUPE,
                "accepted": _counts["accepted"],
                "rejected": _counts["rejected"],
                "rejectedBy": dict(_reasons),
                "deliveries": _deliveries[-50:],
            }
        self._json(200, payload)

    def do_DELETE(self):
        with _lock:
            _deliveries.clear()
            _seen.clear()
            _counts["accepted"] = 0
            _counts["rejected"] = 0
            _reasons.clear()
        self._json(200, {"reset": True})

    def log_message(self, *args):
        """Silence the default access log; emit() is the record that matters."""


if __name__ == "__main__":
    emit({"webhook_sink": "starting", "port": PORT,
          "verifying": bool(SECRET), "toleranceSeconds": TOLERANCE,
          "dedupe": DEDUPE})
    HTTPServer(("", PORT), Handler).serve_forever()
