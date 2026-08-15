// Ramping load. The profile that finds the knee.
//
//   k6 run tools/loadtest/ramp.js
//   k6 run -e MAX_RATE=500 -e CHAOS_LATENCY_MS=3000 tools/loadtest/ramp.js
//
// CONSTANT ARRIVAL RATE, not fixed VUs, and this is the single most important
// line in the file.
//
// With fixed VUs, each virtual user sends a request, waits for the response,
// and only then sends the next. So when the system slows down, the generator
// sends less load - it backs off precisely when the interesting thing starts
// happening, and the collapse hides itself behind a flattering throughput
// number. An arrival-rate executor keeps offering the configured rate
// regardless of how the system is coping, which is what real traffic does, and
// it is the only way to see a queue actually form.
//
// The cost is that k6 must have VUs available to sustain the rate. At 500 rps
// against a 3-second downstream, that is 1500 concurrent requests, so maxVUs is
// generous on purpose. When k6 runs out it prints a dropped_iterations count -
// that number is a measurement of the generator, not of the system, and any run
// with a non-zero one has to be re-run with more VUs before it means anything.

import { createPayment, applyChaosFromEnv, teardownChaos } from './lib/common.js';
import { recordOutcome } from './lib/outcomes.js';

const MAX_RATE = Number(__ENV.MAX_RATE || 1000);
const STAGE = __ENV.STAGE_DURATION || '45s';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || 400),
      maxVUs: Number(__ENV.MAX_VUS || 3000),
      stages: [
        { target: Math.round(MAX_RATE * 0.1), duration: STAGE },
        { target: Math.round(MAX_RATE * 0.25), duration: STAGE },
        { target: Math.round(MAX_RATE * 0.5), duration: STAGE },
        { target: MAX_RATE, duration: STAGE },
        { target: MAX_RATE, duration: STAGE },
      ],
    },
  },
  // No thresholds. This profile exists to observe a collapse, and a threshold
  // would turn the thing being measured into a failure. Phase 3 adds thresholds
  // once there is a defended number worth defending.
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
