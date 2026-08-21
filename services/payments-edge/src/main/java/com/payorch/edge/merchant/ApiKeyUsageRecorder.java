package com.payorch.edge.merchant;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a key authenticated something, without putting a write on every
 * request. Phase 9b.
 *
 * <h2>Why the column exists at all</h2>
 *
 * <p>Starting a rotation is easy. <strong>Finishing</strong> one is the hard
 * part: revoking the old key means asserting that nobody is still using it, and
 * without evidence that assertion is a guess that takes down a merchant's
 * integration during their business hours. So rotations get started and never
 * completed, both keys stay live forever, and the exposure doubles instead of
 * halving. {@code last_used_at} converts the decision into an observation.
 *
 * <h2>Why it is throttled, in a project that measures things</h2>
 *
 * <p>The naive version writes a row on every authenticated request. This edge
 * has been measured at 279 payments/s in experiment 23 with the limits widened,
 * so the naive version adds ~279 writes/s to MySQL — to maintain a timestamp
 * whose consumer is a human deciding whether to revoke a key, and who cannot
 * tell the difference between a value that is current and one that is a minute
 * stale.
 *
 * <p>So: at most one write per key per instance per {@code interval}. The
 * in-memory map is per-process and deliberately not shared — two instances each
 * writing once a minute is two writes a minute, which is still nothing, and
 * coordinating them would cost a Redis round trip to save a MySQL one.
 *
 * <h2>What this deliberately gets wrong</h2>
 *
 * <p>The stamp can be up to {@code interval} stale, and it is lost entirely if
 * the process dies within that window. Both are acceptable for the decision it
 * informs — "has this key been used in the last few hours" — and neither would
 * be acceptable if this were an audit record. It is not one: 9c's detokenization
 * audit log is, and it is written synchronously for exactly that reason.
 */
public class ApiKeyUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyUsageRecorder.class);

    private final MerchantApiKeyRepository keys;
    private final Duration interval;

    /** Key id to the last time this instance wrote a stamp for it. */
    private final Map<UUID, Instant> lastWritten = new ConcurrentHashMap<>();

    public ApiKeyUsageRecorder(MerchantApiKeyRepository keys, Duration interval) {
        this.keys = keys;
        this.interval = interval;
    }

    /**
     * Called on every authenticated request; writes on almost none of them.
     *
     * <p>{@code REQUIRES_NEW} and a swallowed exception, because this runs on
     * the authentication path: a merchant's payment must not fail because a
     * bookkeeping column could not be updated. The failure mode of losing this
     * write is that somebody waits another day before completing a rotation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsed(UUID keyId, Instant now) {
        Instant previous = lastWritten.get(keyId);
        if (previous != null && previous.plus(interval).isAfter(now)) {
            return;
        }

        // Claim the slot BEFORE writing. If the write fails we do not retry on
        // the next request either - a failing write retried at request rate is
        // how a degraded database becomes an outage.
        lastWritten.put(keyId, now);

        try {
            keys.touch(keyId, now);
        } catch (RuntimeException e) {
            log.warn("could not stamp api key usage for {}: {}", keyId, e.toString());
        }
    }
}
