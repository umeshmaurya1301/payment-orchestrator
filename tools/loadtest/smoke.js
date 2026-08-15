// Phase 1 smoke test.
//
// Deliberately not a load test. It runs one virtual user through the whole
// path once and asserts the phase-1 exit criteria; phase 2 adds the load
// profiles that make chaos chaotic rather than merely faulty.
//
//   k6 run tools/loadtest/smoke.js
//   k6 run -e EDGE=http://localhost:8080 tools/loadtest/smoke.js
//
// Exits non-zero if any check fails, so it can gate a `docker compose up`.

import http from 'k6/http';
import { check, fail } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// Endpoints, credential and test card come from the shared module, so this
// script and the three load profiles cannot drift apart on where they point or
// what they send.
import { EDGE, SIMULATOR, API_KEY, TEST_PAN } from './lib/common.js';

export const options = {
  vus: 1,
  iterations: 1,
  // A smoke run that "passes" with a failed check is worse than no smoke run.
  //
  // Only `checks`, deliberately. The obvious companion threshold -
  // `http_req_failed: rate==0.0` - is wrong for this script: two of the
  // assertions below require a non-2xx response (a 401 for an unauthenticated
  // request, and an injected provider failure), so a blanket "no HTTP errors"
  // rule would contradict the very behaviour being verified. The checks are the
  // statement of intent; the raw status-code rate is not.
  thresholds: {
    checks: ['rate==1.0'],
  },
};

function headers(idempotencyKey) {
  return {
    'Content-Type': 'application/json',
    'X-Api-Key': API_KEY,
    'Idempotency-Key': idempotencyKey,
  };
}

function body(amountMinor) {
  return JSON.stringify({
    amountMinor,
    currency: 'INR',
    merchantReference: `smoke-${Date.now()}`,
    card: {
      number: TEST_PAN,
      expiryMonth: 12,
      expiryYear: 2030,
      cvv: '123',
    },
  });
}

export default function () {
  // 1. The chaos endpoint answers, and the provider starts healthy. Running a
  //    smoke test against a simulator left in a degraded state by a previous
  //    experiment is a classic hour lost to nothing.
  const reset = http.del(`${SIMULATOR}/_chaos`);
  check(reset, {
    'chaos endpoint responds': (r) => r.status === 200,
  }) || fail('the chaos control endpoint is not reachable');

  const chaos = http.get(`${SIMULATOR}/_chaos`);
  check(chaos, {
    'provider starts healthy': (r) => {
      const c = r.json();
      return c.latencyMs === 0 && c.errorRate === 0 && c.hangRate === 0 && c.duplicateRate === 0;
    },
  });

  // 2. A payment succeeds end to end and reaches AUTHORIZED.
  const key = uuidv4();
  const created = http.post(`${EDGE}/v1/payments`, body(1000), { headers: headers(key) });

  check(created, {
    'create returns 201': (r) => r.status === 201,
    'payment is AUTHORIZED': (r) => r.json('state') === 'AUTHORIZED',
    'response carries last4 only': (r) => r.json('cardLast4') === '4242',
    'response has no card number': (r) => !r.body.includes(TEST_PAN),
    'response hides the provider': (r) => !r.body.includes('pspId'),
  }) || fail(`create failed: ${created.status} ${created.body}`);

  const paymentId = created.json('id');

  // 3. It can be fetched back.
  const fetched = http.get(`${EDGE}/v1/payments/${paymentId}`, { headers: headers(key) });
  check(fetched, {
    'get returns 200': (r) => r.status === 200,
    'get returns the same payment': (r) => r.json('id') === paymentId,
    'get shows AUTHORIZED': (r) => r.json('state') === 'AUTHORIZED',
  });

  // 4. Replaying the key returns a byte-identical body. The payload below
  //    differs from the original on purpose: if the work were re-run rather
  //    than replayed, the response would differ and this check would fail.
  const replayed = http.post(`${EDGE}/v1/payments`, body(9999), { headers: headers(key) });
  check(replayed, {
    'replay returns 201': (r) => r.status === 201,
    'replay is byte-identical': (r) => r.body === created.body,
  }) || fail('idempotent replay was not byte-identical');

  // 5. Authentication is actually enforced.
  const unauthenticated = http.post(`${EDGE}/v1/payments`, body(1000), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() },
  });
  check(unauthenticated, {
    'unauthenticated request is rejected': (r) => r.status === 401,
  });

  // 6. The chaos endpoint changes behaviour, not just its own state.
  http.post(`${SIMULATOR}/_chaos`, JSON.stringify({
    latencyMs: 0, errorRate: 1.0, hangRate: 0, duplicateRate: 0,
  }), { headers: { 'Content-Type': 'application/json' } });

  const degraded = http.post(`${EDGE}/v1/payments`, body(1000), { headers: headers(uuidv4()) });
  check(degraded, {
    // No resilience exists yet, so the provider's failure surfaces as an
    // unknown outcome rather than being retried away. That is the phase-1
    // behaviour being confirmed, not a defect.
    'injected provider errors reach the payment': (r) =>
      r.status === 201 && r.json('state') === 'UNKNOWN',
  });

  http.del(`${SIMULATOR}/_chaos`);
}
