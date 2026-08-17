#!/usr/bin/env python3
"""Draw the traffic-shift graph from a capture-routing.sh CSV.

    python tools/loadtest/plot-routing.py tools/loadtest/results/5b-tuned

Text rather than PNG, for the same reason as plot-metrics.py: this goes at the
top of the README, where it has to stay readable in a diff, in a terminal, and
on a machine with no image viewer - and where a committed screenshot would
silently rot into a broken link.

WHY THIS DRAWS TWO THINGS AND NOT ONE

The phase-5 plan lists "measuring the shift but not the error rate" as its own
trap: traffic moving while users see errors is not a success, and a graph
showing only the traffic move is the most flattering possible picture of a
system that failed. So every provider's share is drawn against the SAME clock as
the end-user success rate, and the fault and recovery marks sit on both.

Stdlib only.
"""

import csv
import sys
from collections import defaultdict
from pathlib import Path

# The block characters below are not representable in the Windows console's
# default cp1252, and this output is destined for a UTF-8 markdown file rather
# than for a terminal. Without this the script dies on its own chart.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# Eighth-blocks. A share of 0 draws a space rather than the lowest block, so
# "this provider received nothing" is visually distinct from "it received a
# trickle" - which is exactly the distinction the whole phase is about.
BLOCKS = " ▁▂▃▄▅▆▇█"
WIDTH = 72


def load(out_dir):
    """Cumulative counts per timestamp -> per-bucket deltas."""
    rows = defaultdict(dict)
    with open(out_dir / "routing.csv", newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            rows[int(r["ts"])][(r["psp_id"], r["outcome"])] = int(r["attempts"])

    samples = sorted(rows.items())
    deltas, prev = [], None
    for ts, counts in samples:
        if prev is not None:
            d = {}
            for key, value in counts.items():
                gain = value - prev.get(key, value)
                if gain > 0:
                    d[key] = gain
            deltas.append((ts, d))
        prev = counts
    return deltas


def mark(out_dir, name):
    path = out_dir / name
    return int(path.read_text().strip()) if path.exists() else None


def bucket(deltas, width):
    """Fold the samples into `width` columns so the chart fits a README."""
    if not deltas:
        return []
    n = max(1, len(deltas) // width + (1 if len(deltas) % width else 0))
    out = []
    for i in range(0, len(deltas), n):
        chunk = deltas[i:i + n]
        merged = defaultdict(int)
        for _, d in chunk:
            for key, value in d.items():
                merged[key] += value
        out.append((chunk[0][0], dict(merged)))
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    out_dir = Path(sys.argv[1])
    deltas = load(out_dir)
    if len(deltas) < 3:
        print("  not enough samples to plot")
        return 1

    cols = bucket(deltas, WIDTH)
    degraded_at = mark(out_dir, "degraded_at")
    recovered_at = mark(out_dir, "recovered_at")

    providers = sorted({psp for _, d in cols for (psp, _) in d})

    # Per-column share for each provider, and the overall success rate.
    shares = {p: [] for p in providers}
    success = []
    for _, d in cols:
        total = sum(d.values())
        for p in providers:
            got = sum(v for (q, _), v in d.items() if q == p)
            shares[p].append(got / total if total else 0.0)
        ok = sum(v for (_, o), v in d.items() if o == "SUCCESS")
        success.append(ok / total if total else None)

    def event_row():
        row = [" "] * len(cols)
        for i, (ts, _) in enumerate(cols):
            if degraded_at and ts >= degraded_at and (i == 0 or cols[i - 1][0] < degraded_at):
                row[i] = "v"
            if recovered_at and ts >= recovered_at and (i == 0 or cols[i - 1][0] < recovered_at):
                row[i] = "^"
        return "".join(row)

    def spark(values):
        out = []
        for v in values:
            if v is None:
                out.append("?")
            elif v <= 0:
                out.append(" ")
            else:
                idx = 1 + int(v * (len(BLOCKS) - 2))
                out.append(BLOCKS[min(idx, len(BLOCKS) - 1)])
        return "".join(out)

    print()
    print("  %-9s %s" % ("", event_row()))
    print("  %-9s %s" % ("", "v = fault injected   ^ = provider healed"))
    print()
    print("  share of payments routed to each provider")
    for p in providers:
        avg = sum(shares[p]) / len(shares[p])
        print("  %-9s %s  avg %4.0f%%" % (p, spark(shares[p]), 100 * avg))

    print()
    print("  end-user success rate, same clock")
    measured = [s for s in success if s is not None]
    print("  %-9s %s  %4.0f%% mean" % ("success", spark(success),
                                       100 * sum(measured) / max(len(measured), 1)))

    # The three windows, as numbers, because a sparkline is for the shape and a
    # reader still needs the magnitudes.
    def window(lo, hi):
        ok = tot = 0
        for ts, d in cols:
            if (lo is None or ts >= lo) and (hi is None or ts < hi):
                tot += sum(d.values())
                ok += sum(v for (_, o), v in d.items() if o == "SUCCESS")
        return 100 * ok / tot if tot else 0.0

    if degraded_at:
        print()
        print("  before fault %5.1f%%   during %5.1f%%   after %5.1f%%"
              % (window(None, degraded_at),
                 window(degraded_at, recovered_at),
                 window(recovered_at, None)))
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
