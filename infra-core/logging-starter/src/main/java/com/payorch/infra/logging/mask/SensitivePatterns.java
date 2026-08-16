package com.payorch.infra.logging.mask;

import java.util.regex.Pattern;

/**
 * What "sensitive" looks like in free text, defined once.
 *
 * <p>Extracted from {@link Redactor} in phase 4 so the leak test and the runtime
 * masking share one definition rather than two that agree today.
 *
 * <p>The reason is the direction the disagreement would drift. The scanner and
 * the redactor are read by different people at different times, and when they
 * diverge it is overwhelmingly because someone tightened the <em>redactor</em>
 * to stop masking something it was over-matching - leaving the scanner's copy
 * looser, which is harmless, or because someone loosened the scanner to silence
 * a false positive, which is not. A leak test that recognises less than the
 * redactor is a leak test that passes on data the redactor would have masked,
 * which is precisely the case it exists to catch.
 *
 * <p>So there is one copy, it lives with the production code, and the scanner
 * imports it. {@code PanScan} already used {@code Masking.isLuhnValid} for the
 * same reason; this finishes the job for the patterns.
 */
public final class SensitivePatterns {

    /**
     * A run of 13-19 digits, optionally broken by single spaces or hyphens.
     * Bounded repetition, so there is no catastrophic-backtracking risk on the
     * long strings that show up in log output - or on a multi-megabyte database
     * dump, which is what the leak test feeds it.
     *
     * <p>A match is only a card number if it also passes Luhn. The pattern is
     * the cheap filter; {@code Masking.isLuhnValid} is the decision.
     *
     * <p>Bounded by a non-word character rather than merely by a non-digit, for
     * the same reason as {@link #MOBILE_IN} and discovered the same way. A
     * sixty-four character hex hash contains plenty of thirteen-digit runs, and
     * roughly one in ten of those passes Luhn by chance - the phase-4 leak test
     * turned up 112 "card numbers" in one column of idempotency hashes.
     *
     * <p>Luhn is a four-bit checksum, not a proof. It is excellent at rejecting
     * a mistyped card number and useless at rejecting random digits at volume,
     * so the pattern has to carry its share. A real card number is bounded by a
     * quote, a space, a colon or a comma - never by the middle of a hex token.
     *
     * <p>Separators must also be <em>plausible</em>. The original pattern allowed
     * a space or hyphen after any digit, which matches how nobody writes a card
     * number and exactly how people write everything else: the leak test found
     * {@code 28678-9297-18568} - an idempotency key a client had generated as
     * three random numbers - and about one in ten such strings passes Luhn by
     * chance. The same over-match meant {@code Redactor} was masking merchant
     * idempotency keys and order references in log output, destroying the one
     * identifier you would search for while debugging a duplicate payment.
     *
     * <p>So the alternatives below are the three ways a card number is actually
     * written: unbroken, in groups of four, or the 4-6-5 grouping printed on an
     * Amex card.
     */
    public static final Pattern CANDIDATE_PAN = Pattern.compile(
            "(?<![\\w-])(?:"
                    + "\\d{13,19}"                      // unbroken
                    + "|\\d{4}(?:[ -]\\d{4}){2,4}"      // groups of four
                    + "|\\d{4}[ -]\\d{6}[ -]\\d{5}"     // Amex, as printed
                    + ")(?![\\w-])");

    /** IFSC: 4 letters, a literal 0, then 6 alphanumerics. */
    public static final Pattern IFSC =
            Pattern.compile("(?<![A-Z0-9])[A-Z]{4}0[A-Z0-9]{6}(?![A-Z0-9])");

    public static final Pattern EMAIL =
            Pattern.compile("(?<![\\w.+-])[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63})+(?![\\w-])");

    /**
     * A UPI VPA. Distinguished from an email address by the absence of a dot in
     * the handle - {@code user@okhdfcbank} rather than {@code user@bank.com}.
     * Email is matched first, so anything still matching here is a VPA.
     */
    public static final Pattern VPA =
            Pattern.compile("(?<![\\w.+-])[\\w.-]{2,64}@[a-zA-Z]{2,32}(?![\\w.-])");

    /**
     * Indian mobile number, with or without a country code.
     *
     * <p>Bounded by a non-word character, not merely by a non-digit. The looser
     * version - which this was until the phase-4 leak test ran against a real
     * database dump - matches a ten-digit run sitting inside a hex hash, because
     * a letter is not a digit. That produced 157 "mobile numbers" out of one
     * table of idempotency hashes.
     *
     * <p>The false positives were the visible half. The same over-match means
     * {@code Redactor} was masking the middle of hashes in log output, quietly
     * corrupting the one identifier you would want intact while debugging an
     * idempotency problem. A real phone number is not embedded in a word, so
     * requiring a non-word character on each side costs nothing and removes both
     * failures at once.
     */
    public static final Pattern MOBILE_IN =
            Pattern.compile("(?<![\\w+])(?:\\+?91[\\s-]?)?[6-9]\\d{9}(?!\\w)");

    private SensitivePatterns() {
    }
}
