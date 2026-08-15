// Outcome accounting, shared by every profile.
//
// Counters rather than checks, because the thing being counted is not
// pass/fail. A payment that comes back UNKNOWN is not a failed request - the
// edge answered 201 and behaved correctly - but it is also emphatically not a
// success, because a card may have been charged for it. Folding it into a
// single "error rate" would lose exactly the distinction the whole phase is
// about.
//
// The five counters are mutually exclusive and together account for every
// iteration, so `authorized + declined + unknown + rejected + transport` always
// equals the iteration count. Any run where it does not means a response shape
// nobody anticipated, which is itself worth knowing.

import { Counter, Rate, Trend } from 'k6/metrics';

/** The payment succeeded: 201 and AUTHORIZED. */
export const authorized = new Counter('payorch_authorized');

/** The provider said no. A business outcome, not a fault. */
export const declined = new Counter('payorch_declined');

/**
 * The outcome is not known - no answer came back from somewhere in the chain.
 * The most important number in the whole baseline: every one of these is a
 * payment that may have been charged and cannot be safely retried.
 */
export const unknown = new Counter('payorch_unknown');

/** The edge refused the request: 4xx or 5xx. */
export const rejected = new Counter('payorch_rejected');

/** No HTTP response at all - connection refused, reset, or the client gave up. */
export const transportFailure = new Counter('payorch_transport_failure');

/** Share of iterations that ended AUTHORIZED. The headline number. */
export const successRate = new Rate('payorch_success_rate');

/** End-to-end latency, separated from k6's own http_req_duration for clarity. */
export const paymentDuration = new Trend('payorch_payment_duration', true);

export function recordOutcome(response) {
  paymentDuration.add(response.timings.duration);

  if (response.status === 0) {
    transportFailure.add(1);
    successRate.add(false);
    return;
  }
  if (response.status !== 201) {
    rejected.add(1);
    successRate.add(false);
    return;
  }

  // A 201 body is always our own JSON, but a truncated or unexpected body under
  // load must not take the generator down with it.
  let state;
  try {
    state = response.json('state');
  } catch (e) {
    rejected.add(1);
    successRate.add(false);
    return;
  }

  switch (state) {
    case 'AUTHORIZED':
      authorized.add(1);
      successRate.add(true);
      break;
    case 'UNKNOWN':
      unknown.add(1);
      successRate.add(false);
      break;
    default:
      declined.add(1);
      successRate.add(false);
      break;
  }
}
