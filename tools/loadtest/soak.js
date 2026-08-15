// Moderate load, held for a long time. Finds what leaks.
//
//   k6 run tools/loadtest/soak.js
//   k6 run -e DURATION=30m -e RATE=50 tools/loadtest/soak.js
//
// This profile matters more than it looks. Ramp and spike both finish in a
// couple of minutes, and a connection that is never returned to the pool, a
// growing map, or an executor whose queue only creeps upward are all invisible
// at that timescale. They are also the single most common cause of "it worked
// in dev and died overnight".
//
// The rate is deliberately unremarkable. A soak at the knee measures the knee;
// a soak well below it measures whether the system can stay healthy while doing
// something easy, which is the actual question.
//
// What to look at afterwards is not the k6 summary - it will be flat and
// boring, and that is the point. It is the metrics capture: heap after each GC,
// hikaricp_connections_idle, jvm_threads_live. A trend line that only ever goes
// up over thirty minutes is the finding.

import { createPayment, applyChaosFromEnv, teardownChaos } from './lib/common.js';
import { recordOutcome } from './lib/outcomes.js';

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30m',
      preAllocatedVUs: Number(__ENV.PRE_VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 1000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return applyChaosFromEnv();
}

export default function () {
  recordOutcome(createPayment());
}

export function teardown() {
  teardownChaos();
}
