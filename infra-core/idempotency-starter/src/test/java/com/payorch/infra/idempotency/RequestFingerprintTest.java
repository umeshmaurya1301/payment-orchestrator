package com.payorch.infra.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7a. What the fingerprint must and must not do.
 *
 * <p>Two of these are about collisions rather than about correctness in the
 * ordinary sense, because a collision here is not a wrong answer - it is a
 * <em>replay</em>. Two different requests that fingerprint the same are two
 * requests where the second silently receives the first one's response, which
 * is exactly the bug this class was written to end.
 */
class RequestFingerprintTest {

    private final RequestFingerprint fingerprints = new RequestFingerprint("test-secret");

    @Test
    void theSameRequestFingerprintsTheSame() {
        assertThat(fingerprints.of("4200", "INR", "4242424242424242"))
                .isEqualTo(fingerprints.of("4200", "INR", "4242424242424242"));
    }

    @Test
    void aDifferentAmountFingerprintsDifferently() {
        assertThat(fingerprints.of("4200", "INR", "4242424242424242"))
                .isNotEqualTo(fingerprints.of("5000000", "INR", "4242424242424242"));
    }

    @Test
    void aDifferentCardFingerprintsDifferently() {
        assertThat(fingerprints.of("4200", "INR", "4242424242424242"))
                .isNotEqualTo(fingerprints.of("4200", "INR", "4111111111111111"));
    }

    /**
     * THE FIELD-BOUNDARY BUG. Plain concatenation makes {@code ("4200", null)}
     * and {@code ("42", "00")} the same string, so a fingerprint built that way
     * cannot tell an amount of 4200 from an amount of 42 with a suffix - which
     * is the exact class of mistake it exists to detect.
     */
    @Test
    void fieldBoundariesCannotBeShiftedToProduceACollision() {
        assertThat(fingerprints.of("4200", ""))
                .isNotEqualTo(fingerprints.of("42", "00"));
        assertThat(fingerprints.of("a", "b"))
                .isNotEqualTo(fingerprints.of("ab", ""));
    }

    /**
     * A missing merchant reference and an empty one are different requests, so
     * they must not fingerprint alike. Null contributes no bytes but still
     * contributes its separator.
     */
    @Test
    void aNullFieldDiffersFromAnEmptyOne() {
        assertThat(fingerprints.of("4200", (String) null))
                .isNotEqualTo(fingerprints.of("4200", ""));
    }

    /**
     * THE ONE THAT MATTERS FOR CARD DATA.
     *
     * <p>The fingerprint has to be a function of the secret, not only of the
     * material. Without that it is a plain SHA-256 of a body containing a PAN,
     * and with the BIN and last four stored in plain text a few columns away
     * that is under a million candidates - well under a second of laptop time
     * to recover the card number from its own "hash".
     */
    @Test
    void aDifferentSecretProducesADifferentFingerprint() {
        RequestFingerprint other = new RequestFingerprint("a-different-secret");

        assertThat(fingerprints.of("4200", "INR", "4242424242424242"))
                .isNotEqualTo(other.of("4200", "INR", "4242424242424242"));
    }

    /** 64 lowercase hex characters, which is what the CHAR(64) column expects. */
    @Test
    void theFingerprintIsSixtyFourHexCharacters() {
        assertThat(fingerprints.of("4200", "INR")).matches("[0-9a-f]{64}");
    }

    /**
     * A blank secret must be a startup failure, not a silent downgrade. A
     * control that quietly stops being a control is worse than one that refuses
     * to start, because only one of them tells you.
     */
    @Test
    void aBlankSecretIsRefused() {
        assertThatThrownBy(() -> new RequestFingerprint("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret is required");

        assertThatThrownBy(() -> new RequestFingerprint(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The material must not be recoverable by reading the value. */
    @Test
    void theFingerprintDoesNotContainTheCardNumber() {
        assertThat(fingerprints.of("4200", "INR", "4242424242424242"))
                .doesNotContain("4242424242424242")
                .doesNotContain("424242");
    }
}
