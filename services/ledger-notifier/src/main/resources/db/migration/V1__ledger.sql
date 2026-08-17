-- Phase 6e. The double-entry ledger's balances.
--
-- WHY DOUBLE ENTRY AT ALL
--
-- Because it makes "the ledger is correct" a property the system can CHECK
-- rather than a claim somebody makes. Every event produces two legs that sum to
-- zero, so at any moment:
--
--     SELECT SUM(amount_minor) FROM ledger_entry;   -- must be 0
--
-- A single-sided "balance += amount" cannot be audited that way: a lost, dropped
-- or double-applied update leaves a number that is simply wrong, with nothing to
-- compare it against. That invariant is the whole reason the phase-6 exit
-- criterion is worded as "the ledger CONVERGES to correct balances" - convergence
-- is only meaningful if there is something to converge to.
--
-- WHY MYSQL FOR BALANCES AND MONGO FOR THE JOURNAL
--
-- They answer different questions. A balance is small, hot, and must be
-- transactionally consistent with the entries that produced it - that is a
-- relational job. The journal is append-only, never updated, read by date range
-- during an investigation, and grows without limit - that is a document store's
-- job. Splitting them is the phase-6 design and this is the relational half.

CREATE TABLE ledger_account (
    id              BINARY(16)   NOT NULL,

    -- Natural key: 'merchant:<uuid>' or 'settlement:clearing'. A string rather
    -- than a foreign key to merchant, because this service must be able to open
    -- an account for a counterparty it has never heard of - the alternative is
    -- dropping events for a merchant this service has not synced yet.
    account_ref     VARCHAR(96)  NOT NULL,

    currency        CHAR(3)      NOT NULL,

    -- Minor units, signed. BIGINT, not DECIMAL: money in this system is integer
    -- minor units end to end (phase 1), and introducing a decimal here would be
    -- the one place a rounding difference could enter.
    balance_minor   BIGINT       NOT NULL DEFAULT 0,

    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                 ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uq_account (account_ref, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ledger_entry (
    id              BINARY(16)   NOT NULL,

    -- THE IDEMPOTENCY KEY, and the reason this service can survive
    -- at-least-once delivery.
    --
    -- The outbox relay publishes, then marks; a crash between the two
    -- republishes the event. Kafka itself redelivers on rebalance. Both are
    -- designed for rather than engineered away, and this UNIQUE constraint is
    -- what turns "designed for" into something the database enforces: a
    -- duplicate event is a constraint violation, caught and ignored, not a
    -- second credit to a merchant's balance.
    --
    -- Enforced in the DATABASE rather than by a "have I seen this?" SELECT,
    -- because the check and the insert must be one atomic act. Two consumers on
    -- two partitions handling a redelivery concurrently would both pass the
    -- SELECT and both insert.
    event_id        BINARY(16)   NOT NULL,

    account_id      BINARY(16)   NOT NULL,
    payment_id      BINARY(16)   NOT NULL,

    -- Signed. Debits negative, credits positive, and the two legs of one event
    -- sum to zero. There is no `direction` column on purpose: a sign cannot
    -- disagree with itself, whereas a direction column and an unsigned amount
    -- can, and the code that reconciles them is exactly where a bug hides.
    amount_minor    BIGINT       NOT NULL,

    currency        CHAR(3)      NOT NULL,
    entry_type      VARCHAR(32)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    -- One event produces exactly two legs, so the uniqueness is per (event,
    -- account) rather than per event.
    UNIQUE KEY uq_entry_event_account (event_id, account_id),

    KEY idx_entry_payment (payment_id),
    KEY idx_entry_account (account_id, created_at),

    CONSTRAINT fk_entry_account FOREIGN KEY (account_id) REFERENCES ledger_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The clearing account every payment's other leg goes to.
--
-- Seeded here rather than created lazily so the invariant holds from the first
-- event: a ledger whose counterparty account is created on demand has a window
-- where one leg exists and the other does not.
INSERT INTO ledger_account (id, account_ref, currency, balance_minor)
VALUES (UNHEX(REPLACE(UUID(), '-', '')), 'settlement:clearing', 'INR', 0);
