package com.payorch.ledger.webhook;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signature rules, one assertion each.
 *
 * <p>These are the tests the experiment cannot replace. {@code webhook-security.sh}
 * proves the scheme works end to end against an independent Python verifier,
 * which is the claim that matters - but it takes a container, a broker and a real
 * payment to say "rejected", and it cannot say WHY without trusting the same
 * verifier it is testing. Here each refusal has a name, and the tests run in
 * milliseconds, so a regression in the freshness window is not a mystery in a
 * shell script.
 */
class WebhookSignerTest {

    private static final String SECRET = "whsec_local_dev_only";
    private static final String BODY =
            "{\"id\":\"01a01546-f43d-7b4d-bad5-9c290b8e9b62\",\"type\":\"payment.authorized\"}";
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final WebhookSigner signer = new WebhookSigner(SECRET);
    private final Instant now = Instant.parse("2026-08-18T14:00:00Z");

    @Test
    void aSignatureThisSignerProducedVerifies() {
        String header = signer.sign(now, BODY);

        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        assertThat(signer.verify(header, BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.VALID);
    }

    /**
     * The whole point. An attacker who intercepts a webhook and changes the
     * amount has to produce a MAC over the new body, which needs the secret.
     */
    @Test
    void aTamperedBodyIsRejected() {
        String header = signer.sign(now, BODY);
        String tampered = BODY.replace("payment.authorized", "payment.refunded");

        assertThat(signer.verify(header, tampered, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MISMATCH);
    }

    /** Somebody else's secret is somebody else's webhook. */
    @Test
    void aSignatureFromAnotherSecretIsRejected() {
        String header = new WebhookSigner("whsec_someone_else").sign(now, BODY);

        assertThat(signer.verify(header, BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MISMATCH);
    }

    /**
     * A captured delivery, replayed an hour later. Valid MAC, valid body,
     * refused - and this is the only check that refuses it, because everything
     * about the request is genuine.
     */
    @Test
    void aValidSignatureOutsideTheWindowIsStale() {
        String header = signer.sign(now.minus(Duration.ofHours(1)), BODY);

        assertThat(signer.verify(header, BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.STALE);
    }

    /**
     * The other direction, and it is the one a one-sided check misses. A
     * timestamp far in the FUTURE would otherwise be accepted forever - a replay
     * with no expiry date.
     */
    @Test
    void aTimestampFarInTheFutureIsAlsoStale() {
        String header = signer.sign(now.plus(Duration.ofHours(1)), BODY);

        assertThat(signer.verify(header, BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.STALE);
    }

    /**
     * The attack the timestamp-in-the-MAC prevents. An attacker replaying a
     * stale capture rewrites `t` to now; if `t` were only a header, the freshness
     * check would pass and the MAC - computed over the body alone - would still
     * verify.
     */
    @Test
    void movingTheTimestampInvalidatesTheSignature() {
        String original = signer.sign(now.minus(Duration.ofHours(1)), BODY);
        String v1 = original.substring(original.indexOf("v1="));
        String rewritten = "t=" + now.getEpochSecond() + "," + v1;

        assertThat(signer.verify(rewritten, BODY, now, TOLERANCE))
                .as("fresh timestamp, stolen MAC - and the MAC covers the timestamp")
                .isEqualTo(WebhookSigner.Result.MISMATCH);
    }

    @Test
    void anUnsignedRequestIsMissingRatherThanInvalid() {
        assertThat(signer.verify(null, BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MISSING);
        assertThat(signer.verify("  ", BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MISSING);
    }

    /**
     * Garbage in the header must be a named refusal, not an exception. A
     * verifier that throws on a malformed header is a verifier an attacker can
     * turn into a 500 - and a 500 is not a rejection.
     */
    @Test
    void aMalformedHeaderIsRejectedWithoutThrowing() {
        assertThat(signer.verify("nonsense", BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MALFORMED);
        assertThat(signer.verify("t=notanumber,v1=abcd", BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MALFORMED);
        assertThat(signer.verify("t=" + now.getEpochSecond() + ",v1=zzzz", BODY, now, TOLERANCE))
                .as("odd-length and non-hex both parse to MALFORMED, never an exception")
                .isEqualTo(WebhookSigner.Result.MALFORMED);
        assertThat(signer.verify("t=" + now.getEpochSecond(), BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MALFORMED);
    }

    /**
     * A truncated signature must not verify against a prefix of the real one.
     * This is what {@code MessageDigest.isEqual} over the decoded BYTES buys
     * that a {@code startsWith} would not.
     */
    @Test
    void aTruncatedSignatureIsRejected() {
        String header = signer.sign(now, BODY);
        String truncated = header.substring(0, header.length() - 2);

        assertThat(signer.verify(truncated, BODY, now, TOLERANCE))
                .isEqualTo(WebhookSigner.Result.MISMATCH);
    }

    /**
     * An empty secret produces a signature that verifies and protects nothing.
     * Refusing to construct is the only way that cannot happen by accident.
     */
    @Test
    void aSignerCannotBeBuiltWithoutASecret() {
        assertThatThrownBy(() -> new WebhookSigner(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSigner(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Not a property of HMAC - a property of THIS canonical string. The body and
     * the timestamp are joined by a separator, so two different (t, body) pairs
     * cannot produce the same signed bytes by sliding the boundary between them.
     */
    @Test
    void theTimestampAndBodyCannotBeSlidPastEachOther() {
        Instant t = Instant.ofEpochSecond(1786790407L);
        String a = signer.sign(t, ".suffix");
        String b = signer.sign(Instant.ofEpochSecond(17867904L), "07..suffix");

        assertThat(a.substring(a.indexOf("v1=")))
                .isNotEqualTo(b.substring(b.indexOf("v1=")));
    }
}
