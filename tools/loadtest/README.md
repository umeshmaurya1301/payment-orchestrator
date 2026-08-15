# Load tests

k6 scripts. Added in phase 2, then used by every phase after it.

| Script | Profile | Catches |
|---|---|---|
| `smoke.js` | 1 VU | Sanity - does a payment still work at all |
| `ramp.js` | constant arrival rate, 50 -> 1000 rps | Where the knee is |
| `spike.js` | sudden 10x burst | Cold pools, queue buildup, admission control |
| `soak.js` | 30 min at moderate load | Leaks and pool exhaustion, which never show up in a 60-second run |

Use a **constant arrival rate** rather than a fixed VU count for `ramp.js`. With
fixed VUs, a system that slows down also receives less load, which hides the
collapse being measured - the load generator quietly backs off exactly when the
interesting thing starts happening.

Results are written up in [`../../docs/experiments/`](../../docs/experiments/),
not here. Raw output is gitignored.
