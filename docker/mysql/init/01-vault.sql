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

    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (token)
) ENGINE = InnoDB;

-- payments-edge. Writes on tokenization, and can read back what it wrote.
CREATE USER IF NOT EXISTS 'vault_writer'@'%' IDENTIFIED BY 'vault_writer_pw';
GRANT SELECT, INSERT ON payorch_vault.token_vault TO 'vault_writer'@'%';

-- psp-connector. SELECT only.
--
-- This is the grant that makes the design real: a bug that tried to tokenize
-- from the connector, or a compromised connector attempting to plant a row,
-- fails at the database rather than at code review. Note there is no DELETE or
-- UPDATE for anyone - the vault is append-only, so no code path can quietly
-- rewrite the card behind an existing token.
CREATE USER IF NOT EXISTS 'vault_reader'@'%' IDENTIFIED BY 'vault_reader_pw';
GRANT SELECT ON payorch_vault.token_vault TO 'vault_reader'@'%';

-- The application user 'payorch', created by the image from MYSQL_USER, holds
-- ALL PRIVILEGES on the `payorch` database and is granted nothing here. That
-- absence is the control: payment-orchestrator, which owns every payment row,
-- cannot read a card number even if it tried to.

FLUSH PRIVILEGES;
