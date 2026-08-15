#!/usr/bin/env python3
"""Turn a capture-metrics.sh CSV into the handful of numbers a writeup needs.

    python tools/loadtest/summarise-metrics.py out.csv

Prints, per service: peak Hikari active/pending/idle, peak live threads, peak
heap, and the request count delta. Those are the columns of every table in
docs/experiments, and computing them by eye from a 40,000-row CSV is how numbers
end up wrong in a document nobody re-checks.

Deliberately stdlib-only. A tool that needs a pip install is a tool that does not
get run six months later when the graph needs redrawing.
"""

import csv
import sys
from collections import defaultdict


def load(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            try:
                row["value"] = float(row["value"])
                row["timestamp"] = int(row["timestamp"])
            except (ValueError, TypeError):
                continue
            rows.append(row)
    return rows


def peak(rows, service, metric, label_contains=None):
    values = [
        r["value"]
        for r in rows
        if r["service"] == service
        and r["metric"] == metric
        and (label_contains is None or label_contains in r["labels"])
    ]
    return max(values) if values else None


def delta(rows, service, metric, label_contains=None):
    """First-to-last change. For counters, that is the count during the run."""
    series = sorted(
        (
            (r["timestamp"], r["value"])
            for r in rows
            if r["service"] == service
            and r["metric"] == metric
            and (label_contains is None or label_contains in r["labels"])
        )
    )
    if not series:
        return None
    # Summed across label sets at each timestamp, so several endpoints on one
    # service do not silently report only the last one seen.
    by_time = defaultdict(float)
    for timestamp, value in series:
        by_time[timestamp] += value
    ordered = [by_time[t] for t in sorted(by_time)]
    return ordered[-1] - ordered[0]


def fmt(value, unit=""):
    if value is None:
        return "-"
    if unit == "MiB":
        return f"{value / 1048576:.0f} MiB"
    if value == int(value):
        return str(int(value))
    return f"{value:.2f}"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    rows = load(sys.argv[1])
    if not rows:
        print("no usable rows - was the capture running while load was applied?")
        return 1

    services = sorted({r["service"] for r in rows})
    span = max(r["timestamp"] for r in rows) - min(r["timestamp"] for r in rows)
    print(f"capture window: {span}s, {len(rows)} samples\n")

    header = f"{'service':22} {'hikari act':>11} {'hikari pend':>12} {'threads':>8} {'heap':>10} {'requests':>9}"
    print(header)
    print("-" * len(header))

    for service in services:
        print(
            f"{service:22} "
            f"{fmt(peak(rows, service, 'hikaricp_connections_active')):>11} "
            f"{fmt(peak(rows, service, 'hikaricp_connections_pending')):>12} "
            f"{fmt(peak(rows, service, 'jvm_threads_live_threads')):>8} "
            f"{fmt(peak(rows, service, 'jvm_memory_used_bytes', 'area=\"heap\"'), 'MiB'):>10} "
            f"{fmt(delta(rows, service, 'http_server_requests_seconds_count')):>9}"
        )

    print("\npeak values are the maximum observed in any sample, not an average.")
    print("hikari pending is threads parked waiting for a connection - the number")
    print("that says the pool, not the database, is the constraint.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
