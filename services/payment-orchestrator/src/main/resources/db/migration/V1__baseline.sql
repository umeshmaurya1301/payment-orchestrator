-- Flyway baseline. Deliberately empty of DDL.
--
-- Its job in phase 0 is to establish migration discipline before there is
-- anything to migrate: the schema history table exists, Flyway runs on every
-- startup, and from here on every schema change - including the index work in
-- phase 8 - is a numbered migration rather than a hand-run ALTER.
--
-- Phase 1 adds V2 with the real tables: merchant, psp_config, payment,
-- payment_attempt, idempotency_record, and the isolated token_vault, all keyed
-- by UUIDv7 rather than v4 so the clustered primary key stays roughly
-- time-ordered and InnoDB is not forced into constant page splits.

SELECT 1;
