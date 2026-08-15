#!/usr/bin/env python3
"""Draw an ASCII time series from a capture-metrics.sh CSV.

    python tools/loadtest/plot-metrics.py results/00-control/metrics.csv \
           payments-edge hikaricp_connections_pending

Text rather than PNG on purpose. The output of this goes straight into
docs/experiments/*.md, where it stays readable in a diff, in a terminal, and on
a machine that has no image viewer - and where it cannot silently rot into a
broken image link the way a committed screenshot does.

Stdlib only, for the same reason as summarise-metrics.py.
"""

import csv
import sys
from collections import defaultdict

HEIGHT = 12
WIDTH = 68


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2

    path, service, metric = sys.argv[1], sys.argv[2], sys.argv[3]
    label_filter = sys.argv[4] if len(sys.argv) > 4 else None

    by_time = defaultdict(float)
    with open(path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["service"] != service or row["metric"] != metric:
                continue
            if label_filter and label_filter not in row["labels"]:
                continue
            try:
                by_time[int(row["timestamp"])] += float(row["value"])
            except (ValueError, TypeError):
                continue

    if not by_time:
        print(f"no samples for {service} / {metric}")
        return 1

    times = sorted(by_time)
    start = times[0]
    values = [by_time[t] for t in times]

    # Downsample to WIDTH columns by taking the max in each bucket. Max, not
    # mean: this is used to show saturation, and averaging a spike away is
    # exactly the wrong thing for that.
    buckets = [[] for _ in range(WIDTH)]
    span = max(times[-1] - start, 1)
    for t, v in zip(times, values):
        index = min(int((t - start) / span * (WIDTH - 1)), WIDTH - 1)
        buckets[index].append(v)
    series = [max(b) if b else None for b in buckets]

    peak = max(v for v in series if v is not None)
    scale = peak if peak > 0 else 1

    print(f"{service} :: {metric}" + (f" [{label_filter}]" if label_filter else ""))
    print(f"peak {peak:,.0f} over {span}s\n")

    for row in range(HEIGHT, 0, -1):
        threshold = scale * row / HEIGHT
        line = "".join(
            "#" if (v is not None and v >= threshold) else " " for v in series
        )
        print(f"{threshold:9,.0f} |{line}")
    print(f"{0:9,.0f} +{'-' * WIDTH}")
    print(f"{'':10}0s{' ' * (WIDTH - 8)}{span}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
