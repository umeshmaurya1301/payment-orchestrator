package com.payorch.infra.logging.mask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last-resort net. These tests are as much about what it must <em>not</em>
 * do as what it must.
 */
class RedactorTest {

    @ParameterizedTest
    @DisplayName("Luhn-valid card numbers are masked whatever the separator")
    @ValueSource(strings = {
            "4242424242424242",
            "4242 4242 4242 4242",
            "4242-4242-4242-4242",
    })
    void masksLuhnValidPans(String pan) {
        String redacted = Redactor.redact("authorizing card " + pan + " for merchant m-1");

        assertThat(redacted).contains("424242******4242");
        assertThat(redacted).doesNotContain("4242424242424242");
        // Surrounding text survives intact - the net must not eat the log line.
        assertThat(redacted).contains("for merchant m-1");
    }

    @Test
    @DisplayName("a 16-digit run that fails Luhn is left alone")
    void leavesNonLuhnDigitRunsAlone() {
        // Order IDs and trace IDs are long digit runs too. Masking them all
        // would make the logs useless, which is why the checksum gate exists.
        String input = "order 1234567812345678 accepted";

        assertThat(Redactor.redact(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("PANs embedded in exception text are caught")
    void masksPanInsideExceptionMessage() {
        String input = "com.example.PspException: declined for 4242424242424242 (code 51)";

        String redacted = Redactor.redact(input);

        assertThat(redacted).doesNotContain("4242424242424242");
        assertThat(redacted).contains("(code 51)");
    }

    @Test
    @DisplayName("email, VPA, mobile and IFSC are each masked")
    void masksOtherIdentifiers() {
        assertThat(Redactor.redact("contact umesh@example.com"))
                .contains("u***@example.com")
                .doesNotContain("umesh@example.com");

        assertThat(Redactor.redact("paying umesh@okhdfcbank"))
                .contains("u***@okhdfcbank")
                .doesNotContain("umesh@okhdfcbank");

        assertThat(Redactor.redact("sms to 9876543210"))
                .contains("******3210")
                .doesNotContain("9876543210");

        assertThat(Redactor.redact("credit to HDFC0001234"))
                .doesNotContain("HDFC0001234");
    }

    @Test
    @DisplayName("clean text is returned unchanged")
    void passesCleanTextThrough() {
        String input = "payment pay_01H8 authorized by psp-a in 142ms";

        assertThat(Redactor.redact(input)).isSameAs(input);
    }

    @Test
    @DisplayName("multiple PANs in one line are all masked")
    void masksEveryOccurrence() {
        String redacted = Redactor.redact("from 4242424242424242 to 5555555555554444");

        assertThat(redacted).doesNotContain("4242424242424242");
        assertThat(redacted).doesNotContain("5555555555554444");
        assertThat(redacted).contains("424242******4242");
        assertThat(redacted).contains("555555******4444");
    }

    // ---------------------------------------------------------------------
    // Over-matching. Every one of these was a real finding from the phase-4
    // leak test running against a live stack, and every one was ALSO a
    // production masking bug: whatever the scanner reported as a leak, the
    // redactor was busy corrupting in the log line it came from.
    //
    // These are regression tests in both directions - the scanner must not cry
    // wolf, and the redactor must not destroy identifiers people search on.
    // ---------------------------------------------------------------------

    @Test
    void anIdempotencyKeyOfRandomNumbersIsNotACardNumber() {
        // A client generated this as three random numbers joined by hyphens.
        // It passes Luhn - roughly one in ten such strings does - and the old
        // pattern accepted a separator after any digit, so it was masked as a
        // card number in every log line that mentioned it.
        String line = "replaying idempotency key 28678-9297-18568";

        assertThat(Redactor.redact(line))
                .as("nobody writes a card number in groups of 5-4-5")
                .isEqualTo(line);
    }

    @Test
    void aDigitRunInsideAHexHashIsNotACardNumber() {
        String line = "request hash a4482533332274b9f1c8e2d0a7b3c6e5";

        assertThat(Redactor.redact(line))
                .as("a card number is not embedded in the middle of a word")
                .isEqualTo(line);
    }

    @Test
    void aDigitRunInsideAHexHashIsNotAMobileNumber() {
        String line = "request hash c9876543210fe1d2c3b4a5968778695a";

        assertThat(Redactor.redact(line)).isEqualTo(line);
    }

    // ---------------------------------------------------------------------
    // ...and the other direction. Tightening a pattern is only safe if the
    // things it must still catch are pinned.
    // ---------------------------------------------------------------------

    @Test
    void theThreeWaysACardNumberIsActuallyWrittenAreStillMasked() {
        assertThat(Redactor.redact("pan 4242424242424242 authorized"))
                .doesNotContain("4242424242424242");
        assertThat(Redactor.redact("pan 4242 4242 4242 4242 authorized"))
                .doesNotContain("4242 4242 4242 4242");
        assertThat(Redactor.redact("amex 3782 822463 10005 authorized"))
                .as("4-6-5 is how an Amex card is printed")
                .doesNotContain("3782 822463 10005");
    }

    @Test
    void aRealMobileNumberIsStillMasked() {
        assertThat(Redactor.redact("otp sent to 9876543210"))
                .doesNotContain("9876543210");
        assertThat(Redactor.redact("otp sent to +91 9876543210"))
                .doesNotContain("9876543210");
    }

    @Test
    void aVpaIsStillMasked() {
        assertThat(Redactor.redact("collect sent to ramesh.kumar@okhdfcbank"))
                .doesNotContain("ramesh.kumar@okhdfcbank");
    }
}
