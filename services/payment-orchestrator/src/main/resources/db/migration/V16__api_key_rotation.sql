-- Phase 9b, V16. API key rotation: many keys per merchant, with an overlap.
--
-- WHAT WAS ALREADY TRUE, SO THAT THIS IS NOT SOLD AS MORE THAN IT IS
--
-- Keys have been hashed at rest since V2 - SHA-256, one row per merchant, and a
-- dump of `merchant` has never yielded a usable credential. What V2 could not
-- express is the thing that actually reduces exposure: a merchant has exactly
-- ONE key, so replacing it is a simultaneous edit on both sides. Every honest
-- version of that procedure has a window where requests fail, which means it is
-- performed rarely, which means keys live for years. A credential that cannot be
-- rotated without an outage is a credential that does not get rotated.
--
-- V2's own comment said so and named this migration.
--
-- THE SHAPE
--
-- One row per key rather than a second column, because two columns encodes
-- exactly two keys and the states are not symmetric. A rotation needs a key that
-- is accepted but no longer handed out, which is a status, not a slot.
--
--   ACTIVE    accepted, and the one shown to the merchant
--   RETIRING  accepted, deliberately not advertised - the overlap window
--   REVOKED   not accepted, kept for the audit trail
--
-- LAST_USED_AT IS THE POINT OF THE TABLE
--
-- Rotation is not hard to start. It is hard to FINISH, because revoking the old
-- key means asserting nobody is still using it, and without evidence that is a
-- guess that takes down a merchant's integration. `last_used_at` turns the
-- decision into an observation: retire the key, watch until it stops being used,
-- then revoke it.
--
-- That column is written on the authentication path of every request, which is
-- a cost this project does not get to wave through - see ApiKeyAuthFilter for
-- the throttle and docs/experiments/24 for what it measures.

CREATE TABLE merchant_api_key (
    id            BINARY(16)   NOT NULL,
    merchant_id   BINARY(16)   NOT NULL,

    -- SHA-256 hex, exactly as V2 stored it. Not a slow KDF, and V2's comment
    -- explains why: these are generated 128-bit secrets, not human passwords,
    -- so there is no dictionary and the per-request cost of Argon2 would buy
    -- defence against an attack that does not apply here.
    api_key_hash  CHAR(64)     NOT NULL,

    -- Free text, shown to whoever is performing the rotation. A key you cannot
    -- name is a key nobody dares revoke.
    label         VARCHAR(64)  NOT NULL,

    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    -- Set when the key moves to RETIRING: the moment after which it stops being
    -- accepted even if nobody got round to revoking it. NULL for an ACTIVE key.
    --
    -- A deadline rather than a reminder. An overlap window with no expiry is not
    -- a window, it is two permanent keys and twice the exposure - which is the
    -- failure mode of every rotation that is started and never finished.
    expires_at    DATETIME(3)  NULL,

    -- NULL means this key has never authenticated a request.
    last_used_at  DATETIME(3)  NULL,

    PRIMARY KEY (id),

    -- Authentication is one lookup on this index. UNIQUE across all merchants,
    -- not per merchant: a hash collision between two merchants would let one
    -- merchant's key authenticate as another, and the index is what makes that
    -- impossible to insert rather than merely unlikely.
    UNIQUE KEY ux_merchant_api_key_hash (api_key_hash),

    KEY ix_merchant_api_key_merchant (merchant_id, status),

    CONSTRAINT fk_merchant_api_key_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchant (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Backfill. Every existing merchant keeps working, unchanged, with the key it
-- already has - which is the whole point of adding rotation rather than
-- replacing the mechanism.
-- ---------------------------------------------------------------------------

INSERT INTO merchant_api_key (id, merchant_id, api_key_hash, label, status, created_at)
SELECT UNHEX(REPLACE(UUID(), '-', '')),
       m.id,
       m.api_key_hash,
       'original',
       'ACTIVE',
       m.created_at
FROM merchant m;

-- ---------------------------------------------------------------------------
-- And the old column goes. Deliberately in the same migration.
--
-- Leaving it would create two places a credential is checked against, which is
-- how a revoked key keeps working: revoke it here, and the stale column still
-- authenticates. Two sources of truth for an authentication decision is a
-- security bug with a deprecation notice on it.
--
-- The cost is honest: this migration is not reversible without the plaintext
-- keys, which nobody has, because that is the entire idea.
-- ---------------------------------------------------------------------------

ALTER TABLE merchant DROP COLUMN api_key_hash;
