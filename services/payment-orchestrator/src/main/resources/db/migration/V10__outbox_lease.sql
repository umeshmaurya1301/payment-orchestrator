-- Phase 6b, second attempt at the relay.
--
-- WHY THIS MIGRATION EXISTS: THE RELAY BLOCKED THE PAYMENT PATH
--
-- The first relay claimed rows with SELECT ... FOR UPDATE inside a
-- @Transactional method and published to Kafka while still holding the locks.
-- With the brokers down, each pass held them for the producer's full delivery
-- timeout. Measured consequence, from the outbox run of dualwrite-gap.sh:
--
--     4 payments stranded in AUTHORIZING
--     CannotAcquireLockException: Lock wait timeout exceeded
--       [insert into outbox_event ...]
--
-- The relay's range scan over `published_at IS NULL` takes next-key locks on
-- exactly the gap every NEW outbox row is inserted into. So a payment
-- committing its terminal state waited 50 seconds on the relay, timed out, and
-- rolled the whole payment transaction back - leaving the card possibly charged
-- and the payment recorded as neither authorized nor failed.
--
-- That is strictly worse than the event loss this phase set out to fix, and it
-- is the phase-2 rule reappearing: no transaction may be open across a remote
-- call. PaymentPersistence's own javadoc says so. The relay was written with a
-- paragraph explaining why it was different - a background thread, its own
-- connection, a bounded batch - and the measurement disagreed.
--
-- THE FIX: A LEASE INSTEAD OF A LOCK
--
-- Claiming is now a short transaction that stamps `claimed_at` and commits, so
-- the locks are released before anything touches the network. Publishing happens
-- outside any transaction. Marking published is a second short transaction.
--
-- The lease is what keeps that safe with more than one instance: a claimed row
-- is invisible to other relays until its lease expires, so two instances do not
-- both publish it. And because the lease EXPIRES, a relay that dies mid-publish
-- does not strand its batch forever - the rows are retried once the lease runs
-- out, which is the at-least-once behaviour this design already assumes.

ALTER TABLE outbox_event
    ADD COLUMN claimed_at DATETIME(3) NULL AFTER published_at;

-- The claim query is now "unpublished AND (never claimed OR lease expired)".
-- claimed_at joins the index so that stays a range scan rather than a table
-- scan once the table has history in it.
DROP INDEX idx_outbox_unpublished ON outbox_event;
CREATE INDEX idx_outbox_claimable ON outbox_event (published_at, claimed_at, created_at);
