-- A second merchant, so 3e's fairness claim can be measured rather than asserted.
--
-- The per-merchant rate limiter exists to stop one merchant's runaway retry loop
-- from consuming the capacity another merchant paid for. With a single seeded
-- merchant that property is untestable: every request in a load run comes from
-- the same bucket, so the per-merchant limiter and the service-wide endpoint
-- limiter shed exactly the same traffic and the experiment cannot tell which one
-- did the work - or whether the fairness it claims exists at all.
--
-- Two merchants make the question answerable: flood one, keep the other polite,
-- and read the polite one's success rate. See docs/experiments/05-rate-limiters.md.
--
-- Both keys are public by design, like the phase-1 one. They are local test
-- credentials and they are the values the k6 scripts use.
--
--     noisy merchant: pk_test_noisy_merchant_key
--     (phase 1's Dev Merchant keeps pk_test_dev_merchant_key and plays the
--      polite one, so nothing that already exists has to change)

INSERT INTO merchant (id, name, api_key_hash, status)
VALUES (UNHEX('0192ABCD000070008000000000000003'),
        'Noisy Merchant',
        SHA2('pk_test_noisy_merchant_key', 256),
        'ACTIVE');
