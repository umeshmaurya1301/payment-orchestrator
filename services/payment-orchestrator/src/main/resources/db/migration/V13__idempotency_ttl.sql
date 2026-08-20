-- Phase 7c. Claims that expire, and records that do not live forever.
--
-- THE BUG THIS CLOSES
--
-- A claim row is written before the work runs and updated when it finishes. If
-- the process dies in between - SIGKILL, OOM, a pod evicted mid-request - the
-- row is left claimed and unanswered, and nothing ever cleans it up. Every
-- future request with that key finds a claim it cannot take and a response that
-- does not exist, so it waits out its budget and gets a 409. Forever.
--
-- IdempotencyGuard releases the claim when the work throws, which covers the
-- ordinary failure. It cannot cover the process not being there any more, and
-- that is exactly the case an idempotency key exists for: the caller does not
-- know what happened and wants to retry safely.
--
-- THE CLAIM TTL HAS A FLOOR, AND THE FLOOR IS NOT A TUNING CHOICE
--
-- Taking over a claim means deciding the first request is dead. Decide that
-- while it is merely slow and both requests run: the provider is called twice
-- and the merchant is charged twice, which is the single worst outcome this
-- table exists to prevent.
--
-- So claim_expires_at must be further out than the longest a legitimate request
-- could still be running. That is knowable here rather than guessable, because
-- phase 3a put a hard ceiling on it: DEADLINE_MAX_BUDGET_MS, 60 seconds, clamped
-- for every request including one that arrives asking for more. The default
-- claim TTL is fifteen minutes - fifteen times the ceiling - because the cost of
-- being wrong is asymmetric. Too long and a key is unusable for a quarter of an
-- hour after a crash; too short and somebody is charged twice.
--
-- THE SECOND TTL IS A DIFFERENT QUESTION
--
-- expires_at is when the RECORD stops being replayable. A completed record is
-- the stored answer to a question, and keeping every one of them forever means a
-- table that only grows, on the hot path of every payment. Twenty-four hours is
-- the industry-conventional window and is far longer than any retry loop.
--
-- After it, the key is simply new again. That is not a hole: a merchant reusing
-- a key a day later is not retrying, they are issuing a fresh request, and
-- treating it as one is the honest reading.

ALTER TABLE idempotency_record
    -- When an unanswered claim may be taken over by a new request. NULL for
    -- rows written before this migration - they are grandfathered as
    -- never-expiring rather than retroactively declared dead, because a NULL
    -- here means "no opinion" and taking over a claim on no opinion is how the
    -- double charge above happens.
    ADD COLUMN claim_expires_at DATETIME(3) NULL AFTER created_at,

    -- When the whole record stops being replayable and may be swept.
    ADD COLUMN expires_at       DATETIME(3) NULL AFTER completed_at;

-- The sweeper's index. Without it, deleting expired records is a full scan of a
-- table that every payment writes to - a cleanup job that gets slower exactly as
-- it becomes more necessary.
CREATE INDEX idx_idempotency_expires ON idempotency_record (expires_at);
