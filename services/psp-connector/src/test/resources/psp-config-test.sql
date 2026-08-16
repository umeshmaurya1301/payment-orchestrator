-- The subset of psp_config that psp-connector reads, for tests.
--
-- Deliberately only the columns ProviderConfigStore selects. A full copy of the
-- production DDL here would rot the moment a migration added a column the
-- connector does not read, and the drift would be invisible - the test would
-- keep passing while describing a table that no longer exists.
--
-- What this DOES catch is the failure that matters: a column renamed or removed
-- in a migration but still named in the store's SELECT. The context fails to
-- start, in the build, rather than in a container at deploy time.

CREATE TABLE IF NOT EXISTS psp_config (
    psp_id                         VARCHAR(32)  NOT NULL PRIMARY KEY,
    display_name                   VARCHAR(64)  NOT NULL,
    base_url                       VARCHAR(256) NOT NULL,
    enabled                        BOOLEAN      NOT NULL DEFAULT TRUE,
    priority                       INT          NOT NULL DEFAULT 100,
    deadline_slice_ms              INT          NOT NULL DEFAULT 50,
    retry_max_attempts             INT          NOT NULL DEFAULT 2,
    breaker_failure_rate_threshold INT          NOT NULL DEFAULT 50,
    breaker_window_seconds         INT          NOT NULL DEFAULT 30,
    breaker_minimum_calls          INT          NOT NULL DEFAULT 20,
    breaker_wait_open_seconds      INT          NOT NULL DEFAULT 10,
    breaker_half_open_permits      INT          NOT NULL DEFAULT 5,
    bulkhead_max_concurrent        INT          NOT NULL DEFAULT 20,
    bulkhead_max_wait_ms           INT          NOT NULL DEFAULT 250,
    egress_tps                     INT          NOT NULL DEFAULT 200,
    updated_at                     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Port 1 is unbound on every platform, so an adapter that actually tried to
-- call it fails immediately and loudly rather than hanging the test suite.
MERGE INTO psp_config (psp_id, display_name, base_url) KEY (psp_id)
VALUES ('mockpsp', 'Mock PSP', 'http://localhost:1');
