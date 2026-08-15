package com.payorch.infra.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The masking primitives, shared by the Jackson serializer and the Logback
 * output filter so both produce identical output for the same input.
 */
public final class Masking {

    /** Rendered in place of a fully redacted value. */
    public static final String REDACTED = "****";

    private static final int PAN_MIN_DIGITS = 13;
    private static final int PAN_MAX_DIGITS = 19;

    private Masking() {
    }

    /** Applies {@code strategy} to {@code raw}. Null and blank inputs pass through untouched. */
    public static String apply(MaskStrategy strategy, String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        return switch (strategy) {
            case FULL -> REDACTED;
            case PAN -> pan(raw);
            case LAST_FOUR -> lastFour(raw);
            case EMAIL -> email(raw);
            case MOBILE -> mobile(raw);
            case VPA -> vpa(raw);
            case HASH -> hash(raw);
        };
    }

    /**
     * Masks a card number to first-six plus last-four.
     *
     * <p>Anything that is not a plausible PAN length is fully redacted rather
     * than partially revealed - if we cannot confidently identify it, we do not
     * gamble on showing part of it.
     */
    public static String pan(String raw) {
        String digits = digitsOf(raw);
        if (digits.length() < PAN_MIN_DIGITS || digits.length() > PAN_MAX_DIGITS) {
            return REDACTED;
        }
        return digits.substring(0, 6)
                + "*".repeat(digits.length() - 10)
                + digits.substring(digits.length() - 4);
    }

    public static String lastFour(String raw) {
        String trimmed = raw.strip();
        if (trimmed.length() <= 4) {
            return REDACTED;
        }
        return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
    }

    public static String email(String raw) {
        int at = raw.indexOf('@');
        if (at <= 0 || at == raw.length() - 1) {
            return REDACTED;
        }
        return raw.charAt(0) + "***@" + raw.substring(at + 1);
    }

    public static String mobile(String raw) {
        String digits = digitsOf(raw);
        if (digits.length() <= 4) {
            return REDACTED;
        }
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    public static String vpa(String raw) {
        int at = raw.indexOf('@');
        if (at <= 0 || at == raw.length() - 1) {
            return REDACTED;
        }
        return raw.charAt(0) + "***@" + raw.substring(at + 1);
    }

    public static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Strips every non-digit, so {@code "4242 4242-4242 4242"} becomes 16 digits. */
    public static String digitsOf(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Luhn (mod-10) check.
     *
     * <p>This is what stops the output filter from mangling every long number it
     * sees. Order IDs, timestamps and trace IDs are digit runs too; requiring a
     * valid Luhn checksum keeps the false-positive rate low enough that the
     * filter can run on every log line.
     *
     * <p>It is a checksum, not proof: roughly 1 in 10 random digit strings of
     * card length will pass. That is the correct trade-off for a last-resort
     * net - we would rather over-mask a random ID than leak a real PAN.
     */
    public static boolean isLuhnValid(String digits) {
        int length = digits.length();
        if (length < PAN_MIN_DIGITS || length > PAN_MAX_DIGITS) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = length - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int d = c - '0';
            if (doubling) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
