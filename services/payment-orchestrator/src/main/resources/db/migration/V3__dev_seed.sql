-- Local development seed data.
--
-- Kept as a separate migration rather than folded into V2 so it is obvious what
-- is schema and what is data. In a deployment that mattered this would be gated
-- behind a Flyway placeholder or a `dev` migration location; here the whole
-- stack is local by definition, and one always-present merchant is what makes
-- `docker compose up` followed by a curl actually work.
--
-- The API key below is public by design. It is a local test credential, and it
-- is the value the k6 smoke script and the README's curl both use.
--
--     API key: pk_test_dev_merchant_key
--
-- Only its SHA-256 is stored, computed here by MySQL so the plaintext appears
-- exactly once, in this comment, rather than in a hex string nobody can check.

INSERT INTO merchant (id, name, api_key_hash, status)
VALUES (UNHEX('0192ABCD000070008000000000000001'),
        'Dev Merchant',
        SHA2('pk_test_dev_merchant_key', 256),
        'ACTIVE');

-- The single provider phase 1 knows about. It points at mock-psp-simulator.
-- Phase 5 adds a second and a third, which is when `priority` stops being the
-- whole routing decision.
INSERT INTO psp_config (id, psp_id, display_name, enabled, priority, supported_currencies)
VALUES (UNHEX('0192ABCD000070008000000000000002'),
        'mockpsp',
        'Mock PSP',
        1,
        10,
        'INR,USD');
