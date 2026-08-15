// A sudden 10x burst. Finds what is cold.
//
//   k6 run tools/loadtest/spike.js
//   k6 run -e BASE_RATE=50 -e SPIKE_RATE=500 tools/loadtest/spike.js
//
// Different question from ramp.js. A ramp finds the steady-state knee - the
// rate above which the system cannot keep up. A spike finds what is not warm
// when the traffic arrives: connection pools sized for the quiet rate, JIT that
// has not compiled the hot path, a Hikari pool that has to open twenty
// connections at once while requests queue behind it.
//
// A system can pass a ramp to 500 rps and still fall over on a step to 500 rps,
// and the difference between those two results is the entire reason this file
// exists separately.

import { createPayment, applyChaosFromEnv, teardownChaos } from './lib/common.js';
import { recordOutcome } from './lib/outcomes.js';

const BASE_RATE = Number(__ENV.BASE_RATE || 50);
const SPIKE_RATE = Number(__ENV.SPIKE_RATE || BASE_RATE * 10);

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: BASE_RATE,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 3000),
      stages: [
        // Settle, so the spike is measured against a warm system rather than
        // against a cold start.
        { target: BASE_RATE, duration: '45s' },
        // Zero duration: k6 steps straight to the new rate rather than ramping
        // to it. A one-second ramp would be a very short ramp, not a spike.
        { target: SPIKE_RATE, duration: '0s' },
        { target: SPIKE_RATE, duration: '60s' },
        // Back down, to see whether it recovers - and how long that takes.
        // Recovery time is the number people forget to measure.
        { target: BASE_RATE, duration: '0s' },
        { target: BASE_RATE, duration: '60s' },
      ],
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
