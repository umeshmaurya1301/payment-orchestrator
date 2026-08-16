-- Phase 3f: the resilience settings move out of YAML and into the database.
--
-- Every knob added in 3a-3e is currently an environment variable read once at
-- startup, and one value is shared by every provider. Both of those are wrong,
-- and 3d and 3e measured why:
--
--   * 3d: a bulkhead limit sized by Little's law from HEALTHY latency becomes a
--     hard throughput ceiling the moment latency degrades - 20 permits against
--     a 3 s provider is 6.7 rps, so a slow provider turns into a mass-decline
--     event. The fix is not a better constant. There is no constant that is
--     right both when the provider is healthy and when it is not.
--   * 3e: an egress limit is one provider's contracted TPS. Sharing one number
--     across providers means it is wrong for all but one of them.
--
-- So: per provider, and changeable while the system is running. A restart is
-- not an acceptable way to widen a limit during an incident - it resets the
-- connection pools, the circuit breaker windows and the retry budgets, which
-- are precisely the things that are struggling.
--
-- base_url moves here too. It was the last reason psp-connector needed to know
-- anything about a provider from its own configuration file, and leaving it
-- behind would mean adding a provider was a deploy even though everything else
-- about it was a row.

ALTER TABLE psp_config

    -- Where the provider lives. Nullable-with-backfill rather than NOT NULL in
    -- one statement: the column has to exist before the existing row can be
    -- given a value, and a NOT NULL column with no default fails on a table
    -- that already has rows.
    ADD COLUMN base_url VARCHAR(256) NULL AFTER display_name,

    -- 3a. The floor below which this provider's call is declined rather than
    -- started. Per provider because it is a statement about how long THIS
    -- provider needs to be worth calling: 50 ms is generous for a 200 ms
    -- provider and absurd for one that averages 2.5 s.
    ADD COLUMN deadline_slice_ms INT NOT NULL DEFAULT 50,

    -- 3b. Retries against this provider. A provider with a 96% success rate
    -- earns more attempts than one at 99.9%, where a failure is far more likely
    -- to be permanent than transient.
    ADD COLUMN retry_max_attempts INT NOT NULL DEFAULT 2,

    -- 3c. The breaker, per provider. The threshold has to sit above the
    -- provider's own baseline error rate or it flaps continuously - the trap
    -- the phase-3 notes call out, and the reason a single global 50% cannot be
    -- right for a provider that fails 4% of the time and one that fails 0.1%.
    ADD COLUMN breaker_failure_rate_threshold INT NOT NULL DEFAULT 50,
    ADD COLUMN breaker_window_seconds INT NOT NULL DEFAULT 30,
    ADD COLUMN breaker_minimum_calls INT NOT NULL DEFAULT 20,
    ADD COLUMN breaker_wait_open_seconds INT NOT NULL DEFAULT 10,
    ADD COLUMN breaker_half_open_permits INT NOT NULL DEFAULT 5,

    -- 3d. Concurrency, from Little's law on the provider's own contracted
    -- numbers: L = TPS x latency. Stored rather than computed, because the
    -- operator overriding it during an incident is the entire point of 3f.
    ADD COLUMN bulkhead_max_concurrent INT NOT NULL DEFAULT 20,
    ADD COLUMN bulkhead_max_wait_ms INT NOT NULL DEFAULT 250,

    -- 3e. THEIR contracted rate, not ours. This is the number a provider will
    -- block the account over, and it is per provider by definition.
    ADD COLUMN egress_tps INT NOT NULL DEFAULT 200,

    -- When this row last changed, maintained by the server.
    --
    -- There is deliberately no `config_version` column and no trigger to bump
    -- it. The first draft had both, on the reasoning that an operator's
    -- hand-written UPDATE should not have to remember to signal a change - and
    -- then two things became clear. MySQL refuses CREATE TRIGGER without SUPER
    -- while binary logging is on (error 1419), so the trigger would have cost a
    -- server-wide privilege loosening. And it was never load-bearing anyway:
    -- psp-connector re-reads every row on each poll and compares the fields it
    -- actually acts on, precisely so that a version bump from a cosmetic edit
    -- does not rebuild a circuit breaker and discard its failure window.
    --
    -- So the version column would have been a change signal nothing consumed,
    -- maintained by machinery that needed a privilege escalation to exist.
    -- ON UPDATE CURRENT_TIMESTAMP costs nothing and answers the only question
    -- anyone actually asks of it: when did this last move.
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3);

-- Backfill the phase-1 provider, then make base_url mandatory. A provider row
-- with no address is not a provider.
UPDATE psp_config SET base_url = 'http://mock-psp-simulator:8085' WHERE base_url IS NULL;
ALTER TABLE psp_config MODIFY COLUMN base_url VARCHAR(256) NOT NULL;
