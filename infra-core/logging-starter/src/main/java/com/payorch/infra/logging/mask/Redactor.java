package com.payorch.infra.logging.mask;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.payorch.infra.logging.MaskStrategy;
import com.payorch.infra.logging.Masking;

/**
 * Pattern-based redaction of free text. The last-resort net.
 *
 * <p>This runs over log output that has already passed every other control. It
 * exists to catch the cases the earlier controls structurally cannot: an
 * exception message with a PAN in it, a third-party library logging a request
 * body, a developer concatenating a value into a message by hand.
 *
 * <p><strong>This is not the primary control and must never be treated as
 * one.</strong> Tokenization at the edge (phase 1) is what actually keeps card
 * data out of the system; {@code @Sensitive} is what keeps marked fields out of
 * serialized output. A regex over text is guesswork by comparison - it will
 * miss data split across two log lines, data that has been base64'd, and data
 * in a format nobody anticipated. It is here because defence in depth means the
 * last layer still runs when the first two have a bug.
 */
public final class Redactor {

    /**
     * A run of 13-19 digits, optionally broken by single spaces or hyphens.
     * Bounded repetition, so there is no catastrophic-backtracking risk on the
     * long strings that show up in log output.
     */
    private static final Pattern CANDIDATE_PAN =
            Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");

    /** IFSC: 4 letters, a literal 0, then 6 alphanumerics. */
    private static final Pattern IFSC =
            Pattern.compile("(?<![A-Z0-9])[A-Z]{4}0[A-Z0-9]{6}(?![A-Z0-9])");

    private static final Pattern EMAIL =
            Pattern.compile("(?<![\\w.+-])[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63})+(?![\\w-])");

    /**
     * A UPI VPA. Distinguished from an email address by the absence of a dot in
     * the handle - {@code user@okhdfcbank} rather than {@code user@bank.com}.
     * Email is redacted first, so anything still matching here is a VPA.
     */
    private static final Pattern VPA =
            Pattern.compile("(?<![\\w.+-])[\\w.-]{2,64}@[a-zA-Z]{2,32}(?![\\w.-])");

    /** Indian mobile number, with or without a country code. */
    private static final Pattern MOBILE_IN =
            Pattern.compile("(?<!\\d)(?:\\+?91[\\s-]?)?[6-9]\\d{9}(?!\\d)");

    private Redactor() {
    }

    /**
     * Returns {@code input} with every recognised sensitive pattern masked, or
     * the original instance unchanged if nothing matched.
     *
     * <p>Order matters. PANs are handled first because a 16-digit run must not
     * be re-interpreted as something shorter; email before VPA because the VPA
     * pattern is the looser of the two.
     */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = redactPans(input);
        out = IFSC.matcher(out).replaceAll(Masking.REDACTED);
        out = replaceAll(EMAIL, out, MaskStrategy.EMAIL);
        out = replaceAll(VPA, out, MaskStrategy.VPA);
        out = replaceAll(MOBILE_IN, out, MaskStrategy.MOBILE);
        return out;
    }

    /**
     * PAN handling is separate because a match is only masked if it also passes
     * the Luhn checksum. Skipping a match without appending leaves the append
     * position where it was, so unmatched text is carried through by
     * {@code appendTail}.
     */
    private static String redactPans(String input) {
        Matcher matcher = CANDIDATE_PAN.matcher(input);
        StringBuilder out = null;
        while (matcher.find()) {
            String digits = Masking.digitsOf(matcher.group());
            if (!Masking.isLuhnValid(digits)) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(input.length());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(Masking.pan(digits)));
        }
        if (out == null) {
            return input;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceAll(Pattern pattern, String input, MaskStrategy strategy) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length());
        do {
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(Masking.apply(strategy, matcher.group())));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }
}
