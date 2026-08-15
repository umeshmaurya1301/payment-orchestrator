-- Phase 1 core schema.
--
-- Two decisions run through every table here, and both are InnoDB-specific.
--
-- 1. Primary keys are UUIDv7, not v4. In InnoDB the primary key IS the
--    clustered index, so rows are physically ordered by it. A random v4 key
--    scatters inserts across the whole B-tree: pages split, fill factor falls
--    towards 50%, and the working set becomes the entire index rather than its
--    hot right edge. v7 carries a millisecond timestamp in its leading bits, so
--    inserts append the way an AUTO_INCREMENT does while still being generated
--    client-side and safe to expose to callers.
--
-- 2. They are stored as BINARY(16), not CHAR(36). 16 bytes against 36 - and
--    every secondary index in InnoDB carries a full copy of the primary key as
--    its row pointer, so the saving multiplies by the number of secondary
--    indexes rather than being paid once. Phase 8's index measurements are only
--    honest if this was decided before there was data.
--
-- The cost is readability: SELECT shows bytes. Use HEX(id) in ad-hoc queries.
--
-- token_vault is deliberately absent from this file. It lives in a separate
-- database, created by docker/mysql/init with its own credentials, and the
-- application user has no grant on it at all. See docker/mysql/init/01-vault.sql.

CREATE TABLE merchant (
    id           BINARY(16)   NOT NULL,
    name         VARCHAR(128) NOT NULL,

    -- SHA-256 hex of the API key. The key itself is never stored, so a dump of
    -- this table does not let anyone call the API.
    --
    -- A plain digest rather than a slow KDF, on purpose: API keys are
    -- high-entropy secrets we generate, not user-chosen passwords, so there is
    -- no dictionary to run and the brute-force cost is already prohibitive.
    -- Phase 9b adds rotation - two live keys per merchant with an overlap
    -- window - which is what actually reduces exposure here.
    api_key_hash CHAR(64)     NOT NULL,

    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    -- Unique, so authentication is a single point lookup on an index rather
    -- than a scan. It is also what makes an accidentally duplicated key a
    -- write-time failure instead of an ambiguous login.
    UNIQUE KEY uk_merchant_api_key_hash (api_key_hash)
) ENGINE = InnoDB;

CREATE TABLE psp_config (
    id                   BINARY(16)   NOT NULL,
    psp_id               VARCHAR(32)  NOT NULL,
    display_name         VARCHAR(64)  NOT NULL,
    enabled              TINYINT(1)   NOT NULL DEFAULT 1,

    -- Lower is preferred. Phase 1 routes on this alone because there is nothing
    -- else to route on; phase 5 replaces it with health-derived scoring and this
    -- becomes the tie-break.
    priority             INT          NOT NULL DEFAULT 100,

    supported_currencies VARCHAR(128) NOT NULL DEFAULT 'INR',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_psp_config_psp_id (psp_id)
) ENGINE = InnoDB;

CREATE TABLE payment (
    id                 BINARY(16)   NOT NULL,
    merchant_id        BINARY(16)   NOT NULL,

    -- The state machine's current state, as a string rather than an ordinal.
    -- An ordinal column silently re-maps every historical row the moment
    -- someone inserts a value into the middle of the enum, and the damage is
    -- invisible until a report is run months later.
    state              VARCHAR(16)  NOT NULL,

    -- Minor units in an integer. Never a floating-point type: 0.1 + 0.2 is not
    -- 0.3 in binary floating point, and money that does not add up is the one
    -- bug in a payment system nobody forgives.
    amount_minor       BIGINT       NOT NULL,
    currency           CHAR(3)      NOT NULL,

    -- The tokenization boundary, in the schema. There is no column here that
    -- can hold a card number: this row carries the vault reference and the two
    -- fragments PCI-DSS 3.3 permits to be displayed, and nothing else.
    card_token         VARCHAR(48)  NOT NULL,
    card_bin           CHAR(6)      NOT NULL,
    card_last4         CHAR(4)      NOT NULL,

    psp_id             VARCHAR(32)  NULL,
    merchant_reference VARCHAR(128) NULL,

    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                           ON UPDATE CURRENT_TIMESTAMP(3),

    -- Optimistic locking. Present from the first migration because adding it
    -- later means backfilling every row and re-testing every write path. Phase 7
    -- is where it earns its keep.
    version            BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    -- The merchant-facing listing query. Deliberately the only secondary index
    -- on this table for now: phase 8 adds indexes from measured query plans
    -- rather than from guesses, and guesses made here would leave nothing to
    -- measure.
    KEY ix_payment_merchant_created (merchant_id, created_at),
    CONSTRAINT fk_payment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id)
) ENGINE = InnoDB;

CREATE TABLE payment_attempt (
    id           BINARY(16)  NOT NULL,
    payment_id   BINARY(16)  NOT NULL,
    attempt_no   INT         NOT NULL,
    psp_id       VARCHAR(32) NOT NULL,
    operation    VARCHAR(16) NOT NULL,

    -- SUCCESS / FAILED / UNKNOWN. UNKNOWN is not an error state: it means the
    -- provider's answer never arrived, which is a different thing from the
    -- provider saying no. Phase 8's status poller resolves these.
    outcome      VARCHAR(16) NOT NULL,

    provider_ref VARCHAR(64) NULL,
    error_code   VARCHAR(64) NULL,
    latency_ms   INT         NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    -- One row per (payment, operation, attempt). A retry that re-uses an
    -- attempt number is a bug in the retry loop, and this makes it fail at the
    -- database instead of quietly producing two rows that look like two charges.
    UNIQUE KEY uk_attempt_payment_operation_no (payment_id, operation, attempt_no),
    CONSTRAINT fk_attempt_payment FOREIGN KEY (payment_id) REFERENCES payment (id)
) ENGINE = InnoDB;

CREATE TABLE idempotency_record (
    id                    BINARY(16)      NOT NULL,
    merchant_id           BINARY(16)      NOT NULL,
    idempotency_key       VARCHAR(255)    NOT NULL,

    -- The rendered response, kept as bytes. Re-serializing a stored object on
    -- replay does not reproduce the original output - field order and timestamp
    -- formatting drift - and phase 1's exit criterion is byte-identical replay.
    response_status       INT             NULL,
    response_content_type VARCHAR(128)    NULL,
    response_body         VARBINARY(8192) NULL,

    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at          DATETIME(3)     NULL,

    PRIMARY KEY (id),
    -- The actual idempotency control. Enforced here rather than by a
    -- read-then-write in application code, which has a window between the read
    -- and the write that two concurrent requests both fit through.
    UNIQUE KEY uk_idempotency_merchant_key (merchant_id, idempotency_key),
    CONSTRAINT fk_idempotency_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id)
) ENGINE = InnoDB;
