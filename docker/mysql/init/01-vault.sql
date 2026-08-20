-- The token vault: a separate database with its own credentials.
--
-- Why this is not a Flyway migration
-- ----------------------------------
-- Everything else in this system is versioned by Flyway, running as the
-- application user. The vault cannot be, and that is the point. Flyway would
-- need CREATE and ALTER on the vault schema, held by the same connection the
-- application uses for ordinary work - which would hand every code path in
-- payment-orchestrator the ability to read cardholder data. Provisioning the
-- vault out of band, as the platform rather than the application, is what makes
-- "isolated" a grant rather than a comment.
--
-- MySQL runs everything in /docker-entrypoint-initdb.d exactly once: on the
-- FIRST start against an empty data directory. A volume created before this
-- file existed will not have any of it, and the symptom is payments-edge
-- refusing to start with a message telling you to run `docker compose down -v`.
-- That check lives in TokenVault.verifyReachable().
--
-- Passwords here are local development credentials in a file that is committed
-- deliberately, so `docker compose up` works from a clean clone. Phase 9c is
-- where secrets stop being literals.

CREATE DATABASE IF NOT EXISTS payorch_vault
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE payorch_vault;

CREATE TABLE IF NOT EXISTS token_vault (
    -- The token is 'tok_' plus 22 base64url characters of SecureRandom output.
    --
    -- This is the one primary key in the system that is deliberately NOT a
    -- time-ordered UUIDv7. Everywhere else, time ordering buys clustered-index
    -- locality; here it would leak creation time and make neighbouring tokens
    -- guessable from one observed value. Unpredictability is worth more than
    -- insert locality on a table this small and this sensitive.
    token        VARCHAR(48)   NOT NULL,

    -- AES-256-GCM. A fresh 12-byte nonce per row, because GCM's security does
    -- not degrade on nonce reuse, it collapses. The token is used as the
    -- additional authenticated data, so a ciphertext cannot be moved to a
    -- different row and still decrypt.
    pan_iv       VARBINARY(12) NOT NULL,
    pan_cipher   VARBINARY(64) NOT NULL,

    -- Duplicated from the payment row on purpose. It keeps the vault
    -- self-describing for the audit and reconciliation work in phase 8 without
    -- anyone needing to decrypt a single row to know what is in the table.
    bin          CHAR(6)     NOT NULL,
    last4        CHAR(4)     NOT NULL,

    -- The expiry lives here rather than travelling with the payment.
    --
    -- A provider needs it to authorize, so it has to reach psp-connector
    -- somehow, and there are only two options: carry it through every
    -- downstream service and database row, or hand it back alongside the PAN at
    -- detokenization time. The second keeps "downstream carries bin + token +
    -- last4, nothing more" literally true instead of nearly true.
    --
    -- Left in plaintext, unlike the PAN. On its own an expiry date is not
    -- cardholder data worth encrypting, and anyone who can read this column can
    -- already read the row it sits in.
    expiry_month TINYINT UNSIGNED  NOT NULL,
    expiry_year  SMALLINT UNSIGNED NOT NULL,

    -- Phase 9b. The envelope.
    --
    -- pan_cipher above is now encrypted under a per-record DATA key, and this
    -- is that key - itself encrypted under a KEY-ENCRYPTION key held on the
    -- ring. Only the wrapped form is ever stored.
    --
    -- WHY THIS IS WORTH THREE COLUMNS
    --
    -- Not secrecy. pan_cipher was already AES-256-GCM with a per-row nonce and
    -- nothing here makes it harder to break. What it buys is a KEK that can be
    -- ROTATED without rewriting the data: rotation unwraps and re-wraps 48
    -- bytes per row, and never decrypts a card. With one key applied directly,
    -- rotating means decrypting and re-encrypting every PAN in the vault - a
    -- long, stateful job holding plaintext card numbers in memory, which is why
    -- systems built that way do not rotate until an incident forces them to.
    --
    -- PER-RECORD, not one DEK for the table. A table-wide DEK would also make
    -- rotation cheap and would hand back what the scheme buys: one compromised
    -- key exposing every card. Per-record keys bound the blast radius to one
    -- card - and they are what makes phase 9c's crypto-shredding work, because
    -- destroying one record's key destroys one record's data.
    --
    -- WHY NULLABLE
    --
    -- Rows written before phase 9b have no DEK and never will. They were
    -- encrypted directly under the static key in payorch.vault.key, and the
    -- only way to convert them is to decrypt and re-encrypt - the expensive
    -- migration this scheme exists to avoid, which it cannot avoid for the
    -- migration INTO itself. NULL therefore means "phase-1 row, read it with
    -- PanCipher", which TokenVault handles explicitly.
    wrapped_dek  VARBINARY(64) NULL,
    dek_iv       VARBINARY(12) NULL,

    -- Which KEK wrapped this record's DEK. The field that makes rotation
    -- gradual: a record names its own key version, so the ring can gain a new
    -- version and the re-wrap job can catch up over hours with no window in
    -- which the vault is partly unreadable.
    kek_version  VARCHAR(32)   NULL,

    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (token),

    -- The rotation job's working set: "everything not yet on the current KEK".
    -- Low cardinality on its own - there are only ever a handful of versions -
    -- but that is exactly right here, because the query wants a large
    -- contiguous slice rather than a needle, and the alternative is a full scan
    -- of the most sensitive table in the system on every pass.
    KEY idx_vault_kek_version (kek_version)
) ENGINE = InnoDB;

-- payments-edge. Writes on tokenization, and can read back what it wrote.
CREATE USER IF NOT EXISTS 'vault_writer'@'%' IDENTIFIED BY 'vault_writer_pw';
GRANT SELECT, INSERT ON payorch_vault.token_vault TO 'vault_writer'@'%';

-- psp-connector. SELECT only.
--
-- This is the grant that makes the design real: a bug that tried to tokenize
-- from the connector, or a compromised connector attempting to plant a row,
-- fails at the database rather than at code review.
CREATE USER IF NOT EXISTS 'vault_reader'@'%' IDENTIFIED BY 'vault_reader_pw';
GRANT SELECT ON payorch_vault.token_vault TO 'vault_reader'@'%';

-- Phase 9b. The rotation credential, and the collision it had to resolve.
--
-- Until now this file said, correctly: "there is no DELETE or UPDATE for
-- anyone - the vault is append-only, so no code path can quietly rewrite the
-- card behind an existing token." KEK rotation needs UPDATE. Those two
-- statements cannot both be satisfied by a table-level grant, and the tempting
-- resolutions are both bad: granting UPDATE on the table gives away the
-- append-only property that was the point, and moving DEKs to a second table
-- adds a join to the detokenization path to work around a permissions problem.
--
-- COLUMN-LEVEL GRANTS resolve it exactly. This user can rewrite the wrapping
-- and cannot touch pan_cipher, pan_iv, bin, last4 or the expiry. A rotation job
-- running as this user is structurally incapable of altering a card, whatever
-- it is asked to do - the append-only guarantee survives for everything that
-- matters, and the one thing rotation needs to change is the one thing it can.
--
-- Still no DELETE for anyone. Phase 9c's erasure gets its own credential, and
-- it should be a decision made then rather than a privilege lying around now.
CREATE USER IF NOT EXISTS 'vault_rotator'@'%' IDENTIFIED BY 'vault_rotator_pw';
GRANT SELECT ON payorch_vault.token_vault TO 'vault_rotator'@'%';
GRANT UPDATE (wrapped_dek, dek_iv, kek_version)
    ON payorch_vault.token_vault TO 'vault_rotator'@'%';

-- The application user 'payorch', created by the image from MYSQL_USER, holds
-- ALL PRIVILEGES on the `payorch` database and is granted nothing here. That
-- absence is the control: payment-orchestrator, which owns every payment row,
-- cannot read a card number even if it tried to.

FLUSH PRIVILEGES;
