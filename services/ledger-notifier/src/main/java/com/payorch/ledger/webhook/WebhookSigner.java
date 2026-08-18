package com.payorch.ledger.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs an outbound webhook, and verifies one.
 *
 * <h2>The scheme, and why each part of it is there</h2>
 *
 * <pre>
 *   X-Payorch-Signature: t=1786790407,v1=9f2c…
 *
 *   v1 = HMAC-SHA256(secret, "&lt;t&gt;." + rawBody)
 * </pre>
 *
 * <ul>
 *   <li><strong>HMAC, not a hash of secret+body.</strong> A plain
 *       {@code SHA256(secret || message)} is vulnerable to length extension: an
 *       attacker who has one valid signature can produce a valid signature for
 *       a LONGER message without knowing the secret. HMAC's nested construction
 *       exists precisely to remove that, and this is the reason it exists rather
 *       than a convention.</li>
 *   <li><strong>The timestamp is inside the signed material.</strong> If it were
 *       only a header, an attacker replaying a captured request would rewrite it
 *       and the freshness check would be decoration. Signing {@code t.body} makes
 *       "when this was sent" a claim the sender is committed to.</li>
 *   <li><strong>The RAW body, not a re-serialization.</strong> The bytes signed
 *       must be the bytes sent, byte for byte. A verifier that parses JSON and
 *       re-serializes it to check the signature will disagree with the sender
 *       over key order, whitespace or number formatting - and will do so
 *       intermittently, which is the worst way to find out.</li>
 * </ul>
 *
 * <h2>What signing does NOT give you</h2>
 *
 * <p>A signature proves a message came from the holder of the secret and has not
 * been altered. It says nothing about whether it has been <em>delivered
 * before</em>. Freshness bounds the replay window; it does not close it, and
 * inside the window a captured delivery is a genuinely valid request. Only the
 * receiver deduplicating on the event id refuses it, which is why every webhook
 * carries {@code X-Payorch-Event-Id} and why experiment 13 measures the two
 * separately.
 *
 * <h2>Why the verifier lives here at all</h2>
 *
 * <p>Nothing in this service verifies a webhook - it sends them. {@link #verify}
 * exists so the RULES can be unit tested: a tampered body, a stale timestamp, a
 * wrong secret and a truncated header each have a named outcome, asserted in
 * milliseconds rather than by driving a container. The receiver a merchant would
 * actually write is {@code docker/webhook-sink/sink.py}, deliberately in another
 * language and written from the header format rather than from this class - two
 * halves of one codebase sharing one helper always agree, including when both
 * are wrong.
 */
public class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-Payorch-Signature";
    public static final String EVENT_ID_HEADER = "X-Payorch-Event-Id";

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    /** Why a signature was refused. Named, so a test and a log line can say which. */
    public enum Result {
        VALID,
        /** No signature header at all - an unsigned request. */
        MISSING,
        /** Present but not {@code t=<digits>,v1=<hex>}. */
        MALFORMED,
        /** Outside the freshness window. A replay, or a badly wrong clock. */
        STALE,
        /** Well-formed, fresh, and not ours. */
        MISMATCH
    }

    private final byte[] secret;

    public WebhookSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "a webhook signing secret is required - construct no signer at all "
                            + "to send unsigned, rather than signing with an empty key, "
                            + "which produces a signature that verifies and protects nothing");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** The value for {@link #SIGNATURE_HEADER}. */
    public String sign(Instant sentAt, String body) {
        long t = sentAt.getEpochSecond();
        return "t=" + t + ",v1=" + HEX.formatHex(mac(t, body));
    }

    /**
     * Verifies a header against a body.
     *
     * <p>Freshness is checked BEFORE the MAC, which is the cheap check first and
     * also the one that turns a stolen-but-valid request into a rejected one.
     */
    public Result verify(String header, String body, Instant now, Duration tolerance) {
        if (header == null || header.isBlank()) {
            return Result.MISSING;
        }

        String timestamp = null;
        String provided = null;
        for (String piece : header.split(",")) {
            String[] kv = piece.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                timestamp = kv[1];
            } else if ("v1".equals(kv[0])) {
                provided = kv[1];
            }
        }
        if (timestamp == null || provided == null || provided.isEmpty()) {
            return Result.MALFORMED;
        }

        long t;
        try {
            t = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Result.MALFORMED;
        }

        // Absolute difference, so a clock AHEAD of ours is refused too. A
        // one-sided check accepts a signature timestamped next year, which is a
        // replay that never expires.
        if (Math.abs(now.getEpochSecond() - t) > tolerance.toSeconds()) {
            return Result.STALE;
        }

        byte[] expected = mac(t, body);
        byte[] actual;
        try {
            actual = HEX.parseHex(provided);
        } catch (IllegalArgumentException e) {
            return Result.MALFORMED;
        }

        // MessageDigest.isEqual, not Arrays.equals and certainly not String
        // equals. It is documented as constant-time; the others return on the
        // first differing byte, and that timing leaks the signature one byte at
        // a time to anyone willing to send enough requests.
        return MessageDigest.isEqual(expected, actual) ? Result.VALID : Result.MISMATCH;
    }

    private byte[] mac(long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is required of every JRE, so this cannot happen for a
            // reason the caller could act on. Not swallowed: a signer that
            // silently produced no signature would send unsigned webhooks that
            // look signed.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
