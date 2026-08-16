-- Phase 3f: three providers with deliberately different personalities.
--
--   A  99.9% success / 200 ms / 500 TPS   - the good one
--   B  96%   success / 2.5 s  /  50 TPS   - slow, flaky, and tightly rationed
--   C  98%   success / 800 ms / 200 TPS   - unremarkable, which is its own case
--
-- The point of three is that no single resilience configuration is correct for
-- all of them, and until now the system has had exactly one. Every number below
-- is derived from that provider's own contract rather than copied:
--
--   bulkhead_max_concurrent   Little's law on the CONTRACTED numbers:
--                             L = TPS x latency. A: 500 x 0.2 = 100.
--                             B:  50 x 2.5 = 125.  C: 200 x 0.8 = 160.
--                             Note B needs MORE concurrency than A despite
--                             one tenth the throughput - concurrency tracks
--                             latency, and that is the whole reason 3d's
--                             single global 20 was wrong.
--
--   breaker threshold         must sit clear of the provider's OWN baseline
--                             error rate or it flaps. B fails 4% of the time
--                             when perfectly healthy; a 50% global threshold
--                             is 12x its baseline for A and only 7x for B,
--                             which is not the same statement about either.
--
--   deadline_slice_ms         roughly 2.5x expected latency: enough that a
--                             normal call is never refused for lack of budget,
--                             small enough that a request with less than that
--                             left declines instead of starting something it
--                             cannot finish.
--
--   retry_max_attempts        B earns more attempts. At 96% a failure is far
--                             more likely to be transient than at 99.9%, where
--                             a failure usually means something is actually
--                             wrong and retrying just adds load to it.
--
-- Priorities put these BEHIND the phase-1 `mockpsp` provider deliberately.
-- Every experiment from 00 through 05 routes to `mockpsp`, and silently
-- re-routing them would invalidate five writeups to save one UPDATE. 3f's own
-- experiment steers traffic by changing `priority` at runtime, which is a
-- demonstration rather than a workaround.

INSERT INTO psp_config (
    id, psp_id, display_name, base_url, enabled, priority, supported_currencies,
    deadline_slice_ms, retry_max_attempts,
    breaker_failure_rate_threshold, breaker_window_seconds, breaker_minimum_calls,
    breaker_wait_open_seconds, breaker_half_open_permits,
    bulkhead_max_concurrent, bulkhead_max_wait_ms, egress_tps)
VALUES
    -- A: fast and reliable. The tight deadline slice is affordable because the
    -- provider is quick; the high minimum-calls stops a handful of unlucky
    -- requests opening a breaker on a provider that fails once in a thousand.
    (UNHEX('0192ABCD000070008000000000000010'),
     'psp-a', 'Provider A (fast, reliable)', 'http://mock-psp-a:8085',
     1, 20, 'INR,USD',
     500, 2,
     20, 30, 50, 10, 5,
     100, 250, 500),

    -- B: slow and flaky, and rationed to 50 TPS by contract. Everything about
    -- its configuration is patience - a 6 s slice, a longer window, a longer
    -- open period, and fewer half-open probes because each probe costs 2.5 s of
    -- a real request's budget to discover something we could learn more cheaply.
    (UNHEX('0192ABCD000070008000000000000011'),
     'psp-b', 'Provider B (slow, flaky, rationed)', 'http://mock-psp-b:8085',
     1, 30, 'INR,USD',
     6000, 3,
     30, 60, 20, 20, 3,
     125, 1000, 50),

    -- C: the middle. Included precisely because it is unremarkable: a
    -- configuration scheme that only works for the extremes is a scheme tuned
    -- to two examples.
    (UNHEX('0192ABCD000070008000000000000012'),
     'psp-c', 'Provider C (moderate)', 'http://mock-psp-c:8085',
     1, 40, 'INR,USD',
     2000, 2,
     25, 30, 30, 15, 5,
     160, 500, 200);

-- The phase-1 provider keeps the settings 3a-3e were measured with, written
-- down explicitly rather than left on column defaults. A default is a value
-- nobody chose, and every one of these was chosen by an experiment.
UPDATE psp_config
SET deadline_slice_ms = 50,
    retry_max_attempts = 2,
    breaker_failure_rate_threshold = 50,
    breaker_window_seconds = 30,
    breaker_minimum_calls = 20,
    breaker_wait_open_seconds = 10,
    breaker_half_open_permits = 5,
    bulkhead_max_concurrent = 20,
    bulkhead_max_wait_ms = 250,
    egress_tps = 200
WHERE psp_id = 'mockpsp';
