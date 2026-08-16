// One simultaneous burst against a full bucket. The atomicity measurement.
//
// The sustained-load arms of 3e barely separate the atomic limiter from the
// naive one, and the reason is not that the naive one is fine - it is that a
// bucket held permanently empty by 500 rps offers almost no opportunity for the
// race. Over-admission needs several callers to read the same NON-ZERO token
// count before any of them writes back, so the effect lives at the moment a
// bucket has tokens and many callers arrive at once: a deploy, a scheduled batch,
// a retry storm after an outage - precisely the moments a limit is load-bearing.
//
// So this profile does the one thing the others cannot: it fires N requests with
// no ramp at all and counts how many the limiter admitted. Against a burst of
// B tokens, the correct answer is exactly B.
//
//   BURST_SIZE  requests fired simultaneously   (default 600)
//   BURST_KEY   which merchant key to send as   (default the noisy merchant)
//
// Run it against a bucket that has had time to refill - the summary is only
// meaningful if the bucket started full.

import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { EDGE, TEST_PAN } from './lib/common.js';

const BURST_SIZE = Number(__ENV.BURST_SIZE || 600);
const KEY = __ENV.BURST_KEY || 'pk_test_noisy_merchant_key';

export const admitted = new Counter('payorch_burst_admitted');
export const throttled = new Counter('payorch_burst_throttled');
export const other = new Counter('payorch_burst_other');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      // One iteration, but that iteration issues every request at once through
      // http.batch. A per-iteration executor would serialise them no matter how
      // many VUs were allocated, and serialised requests cannot race.
      vus: 1,
      iterations: 1,
      maxDuration: '120s',
    },
  },
};

export default function () {
  const body = JSON.stringify({
    amountMinor: 1000,
    currency: 'INR',
    merchantReference: 'burst',
    card: { number: TEST_PAN, expiryMonth: 12, expiryYear: 2030, cvv: '123' },
  });

  const requests = [];
  for (let i = 0; i < BURST_SIZE; i++) {
    requests.push({
      method: 'POST',
      url: `${EDGE}/v1/payments`,
      body,
      params: {
        headers: {
          'Content-Type': 'application/json',
          'X-Api-Key': KEY,
          // Still unique per request. A shared idempotency key would make every
          // request after the first a replay, and replays are answered from the
          // idempotency table without ever reaching the limiter.
          'Idempotency-Key': uuidv4(),
        },
        tags: { name: 'POST /v1/payments' },
      },
    });
  }

  const responses = http.batch(requests);

  let ok = 0;
  let limited = 0;
  let rest = 0;
  for (const response of responses) {
    if (response.status === 429) {
      limited++;
    } else if (response.status === 201) {
      ok++;
    } else {
      rest++;
    }
  }

  admitted.add(ok);
  throttled.add(limited);
  other.add(rest);

  console.log(`burst ${BURST_SIZE}: admitted ${ok}, throttled ${limited}, other ${rest}`);
}
