package com.payorch.infra.tokenization;

import com.payorch.infra.logging.Masking;

/**
 * The two fragments of a card number that are allowed to travel: the 6-digit
 * BIN and the last 4.
 *
 * <p>PCI-DSS 3.3 permits at most first-six plus last-four to be displayed, and
 * those two fragments carry almost all of the operational value - the BIN
 * identifies the issuer, network and card type (which is what routing in phase 5
 * needs), and the last 4 is what a cardholder recognises on a support call.
 * Everything in between is what has to disappear.
 *
 * @param bin   first six digits
 * @param last4 last four digits
 */
public record Pan(String bin, String last4) {

    private static final int MIN_DIGITS = 13;
    private static final int MAX_DIGITS = 19;

    /**
     * Extracts the travelling fragments from a raw card number.
     *
     * @throws IllegalArgumentException if {@code raw} is not a plausible card
     *         number. The message deliberately does not include the input -
     *         exception text is one of the classic ways a PAN reaches a log
     *         file or an error response.
     */
    public static Pan of(String raw) {
        String digits = Masking.digitsOf(raw == null ? "" : raw);
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
            throw new IllegalArgumentException("card number length is not between 13 and 19 digits");
        }
        if (!Masking.isLuhnValid(digits)) {
            throw new IllegalArgumentException("card number fails the Luhn checksum");
        }
        return new Pan(digits.substring(0, 6), digits.substring(digits.length() - 4));
    }

    /** Strips formatting so what is stored and what is checksummed are the same string. */
    public static String normalise(String raw) {
        return Masking.digitsOf(raw == null ? "" : raw);
    }
}
