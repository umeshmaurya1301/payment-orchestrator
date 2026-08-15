// Shared helpers for every load profile.
//
// The point of a shared module here is not brevity - it is that four scripts
// which claim to be comparable must send the same request and reset the same
// state. A ramp run and a spike run whose payloads differ produce two numbers
// that cannot be put on the same axis, and nothing about the output says so.

import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const EDGE = __ENV.EDGE || 'http://localhost:8080';
export const SIMULATOR = __ENV.SIMULATOR || 'http://localhost:8085';

// The local development credential seeded by V3__dev_seed.sql.
export const API_KEY = __ENV.API_KEY || 'pk_test_dev_merchant_key';

// A Luhn-valid test card. It must never appear in any log line or any table
// outside the vault; tools/panscan is what proves that.
export const TEST_PAN = '4242424242424242';

/**
 * A payment request with a fresh idempotency key.
 *
 * Fresh on every iteration, deliberately. Reusing one key would make every
 * request after the first a replay, and a replay never reaches the orchestrator
 * - the load test would be measuring the idempotency table and nothing else.
 */
export function createPayment(amountMinor = 1000) {
  return http.post(
    `${EDGE}/v1/payments`,
    JSON.stringify({
      amountMinor,
      currency: 'INR',
      merchantReference: `load-${uuidv4()}`,
      card: {
        number: TEST_PAN,
        expiryMonth: 12,
        expiryYear: 2030,
        cvv: '123',
      },
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Api-Key': API_KEY,
        'Idempotency-Key': uuidv4(),
      },
      // Named so every profile reports under the same URL rather than one
      // entry per generated id.
      tags: { name: 'POST /v1/payments' },
    },
  );
}

/**
 * Reads the edge's own health. Used by the MySQL experiment to answer the
 * question the doc calls out: what does a saturated connection pool do to an
 * endpoint that never touches the database?
 */
export function readHealth() {
  return http.get(`${EDGE}/actuator/health`, { tags: { name: 'GET /actuator/health' } });
}

export function setChaos(settings) {
  return http.post(
    `${SIMULATOR}/_chaos`,
    JSON.stringify({
      latencyMs: 0,
      errorRate: 0,
      hangRate: 0,
      duplicateRate: 0,
      ...settings,
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );
}

export function resetChaos() {
  return http.del(`${SIMULATOR}/_chaos`);
}

/**
 * Applies the chaos this run wants, from environment variables, and reports it.
 *
 * Every profile calls this from `setup()`. Baking the reset into the script
 * rather than trusting a human to remember it between runs is the fix for the
 * single most common way an experiment gets silently contaminated - the
 * previous run's 40% error rate still being live during the next run's latency
 * test.
 */
export function applyChaosFromEnv() {
  resetChaos();

  const settings = {
    latencyMs: Number(__ENV.CHAOS_LATENCY_MS || 0),
    errorRate: Number(__ENV.CHAOS_ERROR_RATE || 0),
    hangRate: Number(__ENV.CHAOS_HANG_RATE || 0),
    duplicateRate: Number(__ENV.CHAOS_DUPLICATE_RATE || 0),
  };

  const anyFault =
    settings.latencyMs > 0 ||
    settings.errorRate > 0 ||
    settings.hangRate > 0 ||
    settings.duplicateRate > 0;

  if (anyFault) {
    setChaos(settings);
  }

  const active = http.get(`${SIMULATOR}/_chaos`).json();
  console.log(`chaos active: ${JSON.stringify(active)}`);
  return active;
}

/** Always leave the simulator healthy, whatever the run did to it. */
export function teardownChaos() {
  resetChaos();
}
