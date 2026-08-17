-- Phase 6b. The transactional outbox.
--
-- THE WHOLE IDEA IN ONE SENTENCE: the event is written to THIS database, in the
-- SAME transaction as the payment, so "the payment is authorized" and "an event
-- is owed" become one atomic fact instead of two writes that can disagree.
--
-- Phase 6a measured what two writes cost: 60 terminal payments, 40 events, and a
-- gap of 20 that never closed because nothing recorded the debt. A row in this
-- table IS that record.
--
-- WHY A TABLE RATHER THAN A QUEUE, OR 2PC
--
-- XA across MySQL and Kafka is operationally awful and Kafka is a poor XA
-- participant. Kafka transactions give atomicity between Kafka reads and Kafka
-- writes; the MySQL write is not a Kafka write, so they leave the problem
-- untouched. The outbox sidesteps the question: there is only ever ONE
-- transactional resource, and the second write becomes a CONSEQUENCE of the
-- first rather than a sibling of it.
--
-- The cost is honest and worth stating: at-least-once delivery. A relay can
-- publish and then die before marking the row, so the same event goes out twice.
-- That is designed for rather than engineered away - the producer is idempotent,
-- consumers deduplicate on event_id, and trying to achieve exactly-once by hand
-- is how people build systems that are neither.

CREATE TABLE outbox_event (
    id              BINARY(16)   NOT NULL,

    -- The payment. Also the Kafka partition key, which is what makes per-payment
    -- ordering real: ordering in Kafka is per partition, so every event for one
    -- payment must hash to one partition.
    aggregate_id    BINARY(16)   NOT NULL,

    event_type      VARCHAR(64)  NOT NULL,

    -- The serialized PaymentEvent. One opaque column rather than a column per
    -- field because the relay does not need to understand it - it moves bytes to
    -- a topic - and because an event's shape is a published contract that should
    -- not require a schema migration to extend.
    --
    -- TEXT with a JSON_VALID check, rather than the JSON column type. The JSON
    -- type is the obvious choice and it fights Hibernate's `ddl-auto: validate`,
    -- which this service runs precisely so entities and migrations cannot drift:
    -- a String field against a JSON column is a type mismatch it may refuse to
    -- start on. The check constraint keeps what actually mattered - malformed
    -- JSON is rejected at write time rather than discovered by a consumer - and
    -- gives up only the JSON functions, which nothing here uses.
    --
    -- TOKENS ONLY. No PAN reaches this table, for the same reason no PAN reaches
    -- the DLQ: rows here persist until relayed and are read by humans when
    -- something goes wrong.
    payload         TEXT         NOT NULL,

    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    -- NULL until the relay has published it. This is the queue.
    published_at    DATETIME(3)  NULL,

    -- Publish attempts, so a poison row is visible rather than silently retried
    -- forever at the head of the queue.
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(255) NULL,

    PRIMARY KEY (id),

    -- The relay's only query: unpublished rows, oldest first.
    --
    -- published_at leads the index deliberately. The selective predicate is
    -- "published_at IS NULL", and once the table has millions of relayed rows
    -- and a handful of pending ones, this index lets MySQL find the pending ones
    -- without touching the rest. Leading with created_at would make the scan
    -- proportional to the table's history rather than to its backlog.
    KEY idx_outbox_unpublished (published_at, created_at),

    -- For reading one payment's event history during an investigation.
    KEY idx_outbox_aggregate (aggregate_id),

    CONSTRAINT chk_outbox_payload_json CHECK (JSON_VALID(payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
