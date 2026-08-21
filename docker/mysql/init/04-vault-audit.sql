-- Phase 9c. Who read a card, and when.
--
-- WHY ENCRYPTION DOES NOT ANSWER THIS
--
-- 9b's answer to "is card data safe" is envelope encryption, per-record DEKs and
-- a rotatable KEK. All of that answers "can someone who steals the table read
-- it". None of it answers the question an auditor actually asks first:
--
--     who looked at this card, and why?
--
-- A service holding a legitimate credential, doing exactly what it is authorised
-- to do, one million times instead of once, is invisible to every control built
-- so far. The grants say psp-connector may detokenize. They do not say how often,
-- for which payments, or whether last Tuesday's spike was a batch job or an
-- export.
--
-- WHY THE READER MAY WRITE HERE BUT MAY NOT READ
--
-- The uncomfortable part of any self-reported audit trail: the service being
-- audited is the one writing the record. There is no honest way around that
-- without an out-of-process interceptor, and pretending otherwise would be
-- worse than saying it.
--
-- What CAN be arranged is that the record, once written, is beyond the writer's
-- reach. vault_reader gets INSERT and nothing else - no UPDATE, no DELETE, and
-- deliberately no SELECT. A compromised connector can therefore append noise to
-- its own audit trail, and it cannot erase an entry, alter one, or read back
-- what has been recorded about it to find out what an investigator would see.
--
-- Append-only is not tamper-proof. It is tamper-EVIDENT, which is the property
-- that survives contact with a real incident.

CREATE TABLE IF NOT EXISTS payorch_vault.vault_access_log (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    -- The token, never the PAN. An audit log that records what was read is a
    -- second copy of the thing it is auditing, and it is the copy nobody
    -- remembers to encrypt. This table is deliberately not sensitive: everything
    -- in it is already a reference to a card rather than a card.
    token          VARCHAR(64)  NOT NULL,

    -- Which service, from spring.application.name. Not a user - there are no
    -- humans on this path - and not an IP, which in a container network names a
    -- veth pair rather than an accountable party.
    actor          VARCHAR(64)  NOT NULL,

    -- What it was doing. 'authorize' rather than 'read', because "psp-connector
    -- read a card" is not a finding and "psp-connector read a card outside any
    -- authorization" is.
    purpose        VARCHAR(64)  NOT NULL,

    -- The payment this read belongs to, when the caller knows it. NULL is
    -- allowed and is itself interesting: a detokenization with no payment
    -- attached is the shape an export or a debugging session takes.
    reference      VARCHAR(64)  NULL,

    -- Phase 4's correlation id and phase 6h's traceparent, so a row here joins
    -- to the request that caused it and to the trace that shows what else it
    -- touched. Without these the log answers "who" and not "as part of what".
    correlation_id VARCHAR(64)  NULL,
    trace_id       VARCHAR(64)  NULL,

    -- SUCCESS, UNKNOWN_TOKEN or FAILED.
    --
    -- The failures are the point. A successful read by an authorised service is
    -- the boring case; a run of UNKNOWN_TOKEN from one actor is somebody probing
    -- the token space, and it is the only place in this system that would show.
    outcome        VARCHAR(24)  NOT NULL,

    at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    -- "Everything that touched this token", which is the query an investigation
    -- starts with once it has a disputed payment.
    KEY idx_audit_token (token, at),

    -- "What did this service read in this window" - the volume question, and
    -- the one a scheduled report runs. Actor leads because it is the filter;
    -- `at` follows because it is the range.
    KEY idx_audit_actor_at (actor, at)
) ENGINE = InnoDB;

-- INSERT only. Not SELECT, not UPDATE, not DELETE. See the header.
GRANT INSERT ON payorch_vault.vault_access_log TO 'vault_reader'@'%';

-- payments-edge tokenizes; it never detokenizes, so it gets nothing here. If it
-- ever needs to write an audit row, that is a design change worth noticing at
-- the grant rather than discovering in a log.

-- An auditor credential: reads the log, and can reach nothing else in the vault
-- schema. In particular it has no grant on token_vault, so the account used to
-- investigate card access cannot itself access cards.
CREATE USER IF NOT EXISTS 'vault_auditor'@'%' IDENTIFIED BY 'vault_auditor_pw';
GRANT SELECT ON payorch_vault.vault_access_log TO 'vault_auditor'@'%';

FLUSH PRIVILEGES;
