-- Phase 6k. The tombstone that stops a compensated capture from coming back.
--
-- THE RACE THIS EXISTS FOR
--
-- A capture dead-letters, so the ledger never posts its legs. The saga reverses
-- it at the provider and the ledger posts the reversal. The books are now
-- correct and the payment is finished.
--
-- Then somebody replays the DLQ.
--
-- That replay is the FEATURE built in phase 6f - a human reading the dead-letter
-- queue and pushing the records back through. It has no way to know that the
-- event it is replaying has since been compensated, and the ledger's own
-- idempotency does not help: the CAPTURED event genuinely has never been posted,
-- so `existsByEventId` is false and every duplicate defence in the service says
-- "go ahead". The legs land, clearing is credited for funds that were given
-- back, and the card-network account is debited for a capture that no longer
-- exists. Both accounts stay wrong forever, and - this is the part worth
-- sitting with - SUM(amount_minor) is still exactly zero, because the two legs
-- balance each other. The double-entry invariant cannot see this class of bug at
-- all. Only the tombstone can.
--
-- WHY payment_id AND NOT event_id
--
-- The reversal is compensating the payment's capture, not one particular
-- delivery of one particular message. A retried capture that dead-lettered may
-- have been published more than once by the outbox relay, with different event
-- ids; keying on the payment catches all of them.
--
-- WHAT IT DOES NOT FIX
--
-- A replay that is already in flight when the reversal commits. The tombstone is
-- read in the same transaction that would post the legs, so a replay that read
-- before the insert can still post after it. That window is small, it is not
-- closed here, and pretending otherwise would be worse than naming it: see
-- docs/experiments/16-compensating-reversal.md.

CREATE TABLE reversed_capture (
    -- The payment, and the primary key. One capture per payment in this system,
    -- so a reversal is a fact about the payment rather than a row that could
    -- ever repeat - and making that the PK means a redelivered reversal is an
    -- INSERT that fails on a constraint rather than a second tombstone.
    payment_id      BINARY(16)   NOT NULL,

    -- The reversal event that created this tombstone. Kept for the audit trail:
    -- "why is this capture suppressed" should be answerable from the row itself,
    -- without joining back through the journal.
    event_id        BINARY(16)   NOT NULL,

    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,

    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
