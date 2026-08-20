// Constant concurrency, for the virtual-vs-platform comparison.
//
// WHY NOT ramp.js OR burst.js
//
// ramp.js increases load over time, which is the right shape for finding where
// a system breaks and the wrong one for comparing two builds: the two arms would
// break at different points and the summary numbers would not be on the same
// axis. burst.js fires once with no ramp, which measures admission rather than
// sustained throughput.
//
// What this comparison needs is the one thing neither provides: a FIXED number
// of simultaneous in-flight requests, held for a fixed time, so the only thing
// that differs between arms is the thread model underneath.
//
// WHY NOT THE SHELL LOOP THIS REPLACES
//
// The first version of virtual-vs-platform.sh backgrounded N curls and waited
// for all of them before starting the next wave. That is closed-loop: throughput
// becomes N divided by the SLOWEST request in each wave, so it measures tail
// latency and process-spawn cost rather than server capacity. It reported both
// arms completing exactly 1200 payments - 400 concurrency x 3 waves - which is
// the generator's own ceiling showing through, and it moved when the arms did
// not.
//
// k6 holds VUs genuinely concurrent and reports http_reqs, which is the number
// this benchmark is actually about.
//
//   VUS       simultaneous in-flight requests   (default 400)
//   DURATION  how long to hold them             (default 45s)

import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { createPayment } from './lib/common.js';

const VUS = Number(__ENV.VUS || 400);
const DURATION = __ENV.DURATION || '45s';

export const created = new Counter('payorch_payments_created');
export const rejected = new Counter('payorch_payments_rejected');

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '30s',
    },
  },
  // No thresholds. This profile exists to produce a number for comparison, not
  // to pass or fail - the arms are compared against each other by the shell
  // script, and a threshold here would abort one arm and not the other.
  thresholds: {},
};

export default function () {
  const res = createPayment(4200);

  // 201 is a created payment. 429 is the limiter, which is a different
  // subsystem and must not be counted as throughput - the gates are widened
  // for this benchmark precisely so it should be zero, and counting it
  // separately is what proves that.
  if (res.status === 201) {
    created.add(1);
  } else {
    rejected.add(1);
  }

  check(res, {
    'not a server error': (r) => r.status < 500,
  });
}
