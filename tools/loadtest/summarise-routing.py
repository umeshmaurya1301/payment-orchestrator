"""
Turn a routing capture into the two numbers phase 5 is judged on: where the
traffic went, and what the caller saw while it moved.

    python tools/loadtest/summarise-routing.py tools/loadtest/results/<name>

Reads routing.csv (cumulative per provider and outcome), plus the degraded_at
and recovered_at marks the runner wrote, and reports three windows: before the
fault, during it, and after recovery.

WHY DELTAS ARE COMPUTED HERE AND NOT IN THE CAPTURE

The capture writes cumulative counts. If it had written deltas, a sample it
missed - because MySQL was slow, which is exactly what happens under the load
being measured - would silently become a zero and read as "no traffic" rather
than "no measurement". Differencing here means a gap stays visible as a jump.

THE SHIFT TIME IS MEASURED FROM THE FAULT, NOT FROM THE FIRST SAMPLE

"Traffic shifts within N seconds" is a claim about how long a merchant's
payments keep going to a broken provider. It is measured from the instant the
fault was injected to the first sample in which the primary's share of new
attempts has dropped below half - not from when the graph looks different.
"""

import csv
import sys
from collections import defaultdict
from pathlib import Path

BAR = "#"


def load(out_dir: Path):
    rows = defaultdict(dict)  # ts -> (psp, outcome) -> cumulative
    with open(out_dir / "routing.csv", newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            rows[int(r["ts"])][(r["psp_id"], r["outcome"])] = int(r["attempts"])
    return dict(sorted(rows.items()))


def mark(out_dir: Path, name):
    path = out_dir / name
    return int(path.read_text().strip()) if path.exists() else None


def deltas(samples):
    """Cumulative -> per-interval, dropping the first sample which has no base."""
    out = []
    prev = None
    for ts, counts in samples.items():
        if prev is not None:
            d = {}
            for key, value in counts.items():
                gain = value - prev.get(key, value)
                if gain > 0:
                    d[key] = gain
            out.append((ts, d))
        prev = counts
    return out


def window(rows, lo, hi):
    total = defaultdict(int)
    for ts, d in rows:
        if (lo is None or ts >= lo) and (hi is None or ts < hi):
            for key, value in d.items():
                total[key] += value
    return total


def report(label, totals):
    attempts = sum(totals.values())
    print(f"\n  {label}  ({attempts} attempts)")
    if not attempts:
        print("    (none)")
        return
    by_psp = defaultdict(int)
    for (psp, _), v in totals.items():
        by_psp[psp] += v
    for psp in sorted(by_psp, key=lambda p: -by_psp[p]):
        share = 100 * by_psp[psp] / attempts
        ok = totals.get((psp, "SUCCESS"), 0)
        bad = sum(v for (p, o), v in totals.items() if p == psp and o != "SUCCESS")
        print("    %-10s %6.1f%%  %-22s  success %5.1f%%"
              % (psp, share, BAR * max(1, round(share / 4)),
                 100 * ok / max(ok + bad, 1)))
    ok = sum(v for (_, o), v in totals.items() if o == "SUCCESS")
    print("    %-10s %6.1f%%   <- what the caller saw" % ("OVERALL", 100 * ok / attempts))


def share_of(d, primary):
    total = sum(d.values())
    if total == 0:
        return None
    return sum(v for (p, _), v in d.items() if p == primary) / total


def shift_time(rows, degraded_at, primary):
    """Seconds from the fault until the primary carries HALF ITS FORMER SHARE.

    Measured relative to what the primary was carrying before the fault, not
    against a fixed 50%. The first version used an absolute threshold and
    reported "shifted in 0s" for the health-weighted arm - correctly, and
    uselessly: weighted routing had the primary on 39% before anything went
    wrong, so it was already under 50% and the test passed before the fault was
    even injected. A threshold that a passing system satisfies at rest measures
    nothing.
    """
    if degraded_at is None:
        return None, None

    before = [share_of(d, primary) for ts, d in rows
              if ts < degraded_at and sum(d.values()) >= 5]
    before = [s for s in before if s is not None]
    if not before:
        return None, None
    baseline = sum(before) / len(before)
    target = baseline / 2

    for ts, d in rows:
        if ts < degraded_at:
            continue
        if sum(d.values()) < 5:
            continue
        share = share_of(d, primary)
        if share is not None and share <= target:
            return ts - degraded_at, baseline
    return None, baseline


def main():
    out_dir = Path(sys.argv[1])
    samples = load(out_dir)
    if len(samples) < 3:
        print("  not enough samples to say anything")
        return
    rows = deltas(samples)
    degraded_at = mark(out_dir, "degraded_at")
    recovered_at = mark(out_dir, "recovered_at")
    primary = sys.argv[2] if len(sys.argv) > 2 else "psp-a"

    report("BEFORE the fault", window(rows, None, degraded_at))
    report("DURING the fault", window(rows, degraded_at, recovered_at))
    report("AFTER recovery", window(rows, recovered_at, None))

    print()
    shifted, baseline = shift_time(rows, degraded_at, primary)
    if baseline is not None:
        print("  %s carried %.1f%% of attempts before the fault" % (primary, 100 * baseline))
    if shifted is None:
        print("  TRAFFIC NEVER SHIFTED - %s never fell to half of that." % primary)
        print("  This is the baseline that phase 5 exists to change; if this run was")
        print("  supposed to be health-routed, it did not route.")
    else:
        print("  traffic halved off %s in %ds (relative to its own pre-fault share)"
              % (primary, shifted))


if __name__ == "__main__":
    main()
