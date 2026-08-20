package com.payorch.infra.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What a request asked for, as a fixed-width value that can be compared later.
 *
 * <p>Phase 7a. It exists so that reusing an idempotency key with a
 * <em>different</em> payload is caught. Until now that case replayed the first
 * response as though the second request had been honoured - the merchant asks
 * to charge INR 50,000, receives the stored 201 for the INR 42 payment they sent
 * an hour earlier, and both sides believe something happened that did not.
 *
 * <h2>Why this is an HMAC and not a SHA-256</h2>
 *
 * <p>Because the material contains a card number, and a bare digest of a card
 * number is not a one-way function in any sense that matters.
 *
 * <p>A PAN is at most 19 digits, and most of them are already known to anyone
 * holding this table: the BIN and the last four are stored in plain text
 * alongside, by design, since phase 1. That leaves roughly <strong>six unknown
 * digits</strong>, and the last of those is a Luhn check digit. Under a million
 * candidates, one SHA-256 each - a laptop finishes in well under a second, and
 * the "hash" hands back the card number it was supposed to protect. The same
 * argument applies to every scheme that hashes a PAN without a key, and it is
 * why PCI DSS treats such a hash as cardholder data rather than as a safe
 * derivative.
 *
 * <p>Keying the hash removes the offline attack entirely: without the secret
 * there is nothing to brute-force against, because the attacker cannot compute
 * a candidate's fingerprint at all. The cost is a secret that has to exist and
 * be rotated, which is a real operational cost and the right one to pay.
 *
 * <h2>Semantic material, not raw bytes</h2>
 *
 * <p>The tempting alternative is to hash the raw request body, exactly as
 * {@link ReplayableResponse} stores raw response bytes. It is the wrong choice
 * here, and the asymmetry is deliberate.
 *
 * <p>A response is <em>output</em>, and byte-identical replay is the promise. A
 * request is <em>input</em>, and two byte-different requests can be the same
 * request: a client that upgrades its JSON library and starts emitting fields in
 * a different order, or adds a trailing newline, has not changed what it is
 * asking for. Fingerprinting raw bytes would answer 422 to that retry - and a
 * 422 on a retry is a genuinely bad outcome, because the merchant is now
 * holding a key they cannot use and cannot determine whether the payment
 * exists. Being strict about content and forgiving about formatting is the only
 * version of this that helps.
 *
 * <p>So the caller names the fields that determine the effect, in a fixed order,
 * and gets a value that changes when any of them changes. What is NOT in the
 * material is as much a decision as what is - see {@code PaymentsController}.
 */
public final class RequestFingerprint {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Marks a field that was present, and one that was absent.
     *
     * <p>The distinction is not cosmetic: "no merchant reference" and "an empty
     * merchant reference" are different requests, and an encoding that cannot
     * tell them apart replays one as the other.
     */
    private static final byte PRESENT = 1;
    private static final byte ABSENT = 0;

    private final SecretKeySpec key;

    public RequestFingerprint(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "an idempotency fingerprint secret is required; without one the "
                            + "fingerprint is a plain hash of a card number, which is "
                            + "recoverable in under a second - see the class javadoc");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * @param fields the values that determine what the request does, in a fixed
     *               order. A null is encoded distinctly from an empty string,
     *               because "no merchant reference" and "an empty merchant
     *               reference" are different requests
     * @return 64 lowercase hex characters
     */
    public String of(String... fields) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            // LENGTH-PREFIXED, not separated, and the difference is a real bug
            // rather than a style preference. A separator byte only works if it
            // cannot appear inside a field, and these fields are caller-supplied
            // text - a merchant reference is free-form. Length prefixing has no
            // such assumption: the decoder always knows where a field ends
            // because it was told before it started.
            //
            // Caught by RequestFingerprintTest. The first version of this method
            // wrote a separator after each field and nothing for a null, which
            // made ("4200", null) and ("4200", "") the same material.
            for (String field : fields) {
                if (field == null) {
                    mac.update(ABSENT);
                    continue;
                }
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                mac.update(PRESENT);
                // Big-endian, four bytes. Not the string form of the length,
                // which would need a separator of its own and reintroduce the
                // problem one level down.
                mac.update((byte) (bytes.length >>> 24));
                mac.update((byte) (bytes.length >>> 16));
                mac.update((byte) (bytes.length >>> 8));
                mac.update((byte) bytes.length);
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is required of every JVM, and the key was validated in
            // the constructor. Reaching here means the platform is broken in a
            // way no caller can handle.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
