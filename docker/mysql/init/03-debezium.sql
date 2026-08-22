-- Phase 6c. The account Debezium reads the binlog with.
--
-- A THIRD ACCOUNT, and the reason is the same one phases 0 and 3f gave for the
-- first two: each consumer gets exactly the privileges its job needs, so a
-- compromise of one is bounded by what that one could do.
--
--   vault_writer   INSERT on the token vault, nothing else
--   vault_reader   SELECT on the token vault, nothing else
--   config_reader  SELECT on psp_config, nothing else
--   debezium       the binlog, and SELECT on the outbox
--
-- WHY THIS ONE IS DIFFERENT AND SLIGHTLY UNCOMFORTABLE
--
-- REPLICATION SLAVE and REPLICATION CLIENT are GLOBAL privileges - they cannot
-- be scoped to a database, let alone a table. So this account can read the
-- binlog for the entire server, which includes the token vault's writes.
--
-- That is a real property of CDC rather than a mistake in this file, and it is
-- one of the costs the phase-6 comparison is supposed to surface: the polling
-- relay needs SELECT on one table, and CDC needs a firehose of every change on
-- the server. On a shared database that is a conversation with a security team.
-- Recorded here rather than discovered later.
--
-- The table grant is still scoped, so what Debezium can SNAPSHOT is limited to
-- the outbox even though what it can STREAM is not.

CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium';

-- Global, unavoidably. See above.
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';

-- Debezium reads table metadata during snapshot and schema recovery.
--
-- Scoped to payorch.*, not the single outbox_event table: on a truly fresh
-- volume this script runs before payment-orchestrator's own Flyway migration
-- has created outbox_event, so a table-scoped GRANT fails with ERROR 1146 and
-- aborts the whole container. payorch.* still excludes the token vault (a
-- separate database), so the isolation this file is otherwise careful about
-- - "the polling relay needs SELECT on one table" - just widens to one table
-- becoming "the rest of this database's tables", not the vault.
GRANT SELECT ON payorch.* TO 'debezium'@'%';

-- RELOAD is used for FLUSH TABLES WITH READ LOCK during an initial snapshot.
-- Granted because the alternative is a snapshot mode that can miss rows written
-- while it runs, and a CDC pipeline that starts by losing events would be a
-- strange way to fix event loss.
GRANT RELOAD ON *.* TO 'debezium'@'%';

FLUSH PRIVILEGES;
