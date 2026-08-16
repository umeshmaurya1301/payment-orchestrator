// Two merchants, one of them badly behaved.
//
// The profile 3e's fairness claim needs. Every other script in this directory
// sends all its traffic as one merchant, which makes the per-merchant limiter
// and the service-wide endpoint limiter indistinguishable: both shed the same
// requests, and the run cannot say which one did the work or whether "fairness"
// is a property this system actually has.
//
// So: one merchant floods, one stays inside any reasonable allowance, and the
// number that matters is the POLITE merchant's success rate. That is the whole
// experiment. A system without per-merchant limiting will fail the polite
// merchant along with the noisy one, and it will look like an outage rather than
// like one caller's problem.
//
//   NOISY_RATE   requests/s from the runaway merchant   (default 400)
//   POLITE_RATE  requests/s from the well-behaved one   (default 10)
//   DURATION     how long both run for                  (default 90s)

import { Counter, Rate, Trend } from 'k6/metrics';
import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { EDGE, TEST_PAN, setChaos, resetChaos } from './lib/common.js';

const POLITE_KEY = __ENV.POLITE_KEY || 'pk_test_dev_merchant_key';
const NOISY_KEY = __ENV.NOISY_KEY || 'pk_test_noisy_merchant_key';

const NOISY_RATE = Number(__ENV.NOISY_RATE || 400);
const POLITE_RATE = Number(__ENV.POLITE_RATE || 10);
const DURATION = __ENV.DURATION || '90s';

// Separate series per merchant. One aggregate success rate would average the
// victim together with the offender and report a number describing neither.
export const politeAuthorized = new Counter('payorch_polite_authorized');
export const politeThrottled = new Counter('payorch_polite_throttled');
export const politeFailed = new Counter('payorch_polite_failed');
export const politeSuccess = new Rate('payorch_polite_success_rate');
export const politeDuration = new Trend('payorch_polite_duration', true);

export const noisyAuthorized = new Counter('payorch_noisy_authorized');
export const noisyThrottled = new Counter('payorch_noisy_throttled');
export const noisyFailed = new Counter('payorch_noisy_failed');
export const noisySuccess = new Rate('payorch_noisy_success_rate');

export const options = {
  scenarios: {
    // Constant arrival rate, not fixed VUs, for both. With fixed VUs a merchant
    // being throttled would speed up - shorter responses, more iterations - and
    // the offered load would depend on the limiter under test. That is the
    // closed-loop trap phase 2 called out, and it is worse here than usual:
    // the noisy merchant is meant to be a fixed insult, not a reactive one.
    noisy: {
      executor: 'constant-arrival-rate',
      rate: NOISY_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 200,
      maxVUs: 2000,
      exec: 'noisyMerchant',
    },
    polite: {
      executor: 'constant-arrival-rate',
      rate: POLITE_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 200,
      exec: 'politeMerchant',
    },
  },
};

export function setup() {
  // A slow provider, not a broken one. Concurrency has to grow for the edge to
  // be under real pressure, and only latency does that - matching 3d's setup so
  // the two experiments are on the same axis.
  const latencyMs = Number(__ENV.CHAOS_LATENCY_MS || 0);
  if (latencyMs > 0) {
    setChaos({ latencyMs });
    console.log(`chaos: provider latency ${latencyMs}ms`);
  }
}

export function teardown() {
  resetChaos();
}

function pay(apiKey) {
  return http.post(
    `${EDGE}/v1/payments`,
    JSON.stringify({
      amountMinor: 1000,
      currency: 'INR',
      merchantReference: `fair-${uuidv4()}`,
      card: { number: TEST_PAN, expiryMonth: 12, expiryYear: 2030, cvv: '123' },
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Api-Key': apiKey,
        'Idempotency-Key': uuidv4(),
      },
      tags: { name: 'POST /v1/payments' },
    },
  );
}

// 429 is counted apart from every other failure on purpose. Being throttled is
// the limiter working as designed; a 5xx or a dropped connection is the failure
// the limiter was supposed to prevent. Folding them together would let a run
// that crashed the edge score the same as one that shed load cleanly.
function record(response, authorized, throttled, failed, success, duration) {
  if (duration) {
    duration.add(response.timings.duration);
  }
  if (response.status === 201) {
    let state = 'UNPARSEABLE';
    try {
      state = response.json('state');
    } catch (e) {
      // fall through as a failure
    }
    if (state === 'AUTHORIZED') {
      authorized.add(1);
      success.add(true);
      return;
    }
    failed.add(1);
    success.add(false);
    return;
  }
  if (response.status === 429) {
    throttled.add(1);
    success.add(false);
    return;
  }
  failed.add(1);
  success.add(false);
}

export function noisyMerchant() {
  record(pay(NOISY_KEY), noisyAuthorized, noisyThrottled, noisyFailed, noisySuccess, null);
}

export function politeMerchant() {
  record(pay(POLITE_KEY), politeAuthorized, politeThrottled, politeFailed,
         politeSuccess, politeDuration);
}
