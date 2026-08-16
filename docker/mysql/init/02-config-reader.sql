-- Phase 3f. psp-connector's credentials for reading its own resilience config.
--
-- The connector has had no application database at all until now - only the
-- read-only vault connection - and 3f gives it a second one for exactly one
-- table: SELECT on payorch.psp_config, and nothing else. The connector has no
-- business reading `payment`, `merchant` or `idempotency_record`, and the most
-- reliable way to guarantee that is for the credential to be unable to.
--
-- Note what this credential still cannot reach: payorch_vault.token_vault needs
-- `vault_reader`, a separate user with a separate password. Two read-only
-- connections from one service, with disjoint grants, because "read only" is not
-- one permission - a credential that can read cards is not interchangeable with
-- one that can read timeouts.
--
-- WHY THE GRANT IS NOT HERE
--
-- It cannot be. MySQL 8.4 refuses `GRANT ... ON payorch.psp_config` with
-- ER_NO_SUCH_TABLE when the table does not exist yet, and this file runs from
-- the entrypoint on an empty data directory - long before payment-orchestrator's
-- Flyway migrations create anything. Older MySQL allowed granting on a table
-- that did not exist; 8.x does not, and the failure is a hard one: the
-- entrypoint aborts and the container exits 1.
--
-- So the user is created here, with no privileges at all, and the narrow grant
-- lives in V7__config_reader_grant.sql where the table is guaranteed to exist.
-- The GRANT OPTION below is what lets the schema owner issue it.
--
-- Creating the user here rather than in the migration too is deliberate: an
-- account is an infrastructure fact and a grant is a schema fact. A migration
-- that had to invent the account would also own its password, which is how a
-- credential ends up hard-coded in a file that gets replayed on every
-- environment.

CREATE USER IF NOT EXISTS 'config_reader'@'%' IDENTIFIED BY 'config_reader_pw';

-- The application user already holds ALL PRIVILEGES on this schema, granted by
-- the image from MYSQL_USER. GRANT OPTION lets it delegate a strict subset of
-- what it already has - it cannot hand out anything it does not hold, and it
-- has nothing at all on payorch_vault. So this widens what `payorch` can
-- delegate within its own schema and does not widen what it can reach.
GRANT ALL PRIVILEGES ON payorch.* TO 'payorch'@'%' WITH GRANT OPTION;

FLUSH PRIVILEGES;
