package com.payorch.infra.logging;

/**
 * How a {@link Sensitive} value is rendered once masked.
 *
 * <p>Choosing a strategy is a disclosure decision, not a formatting one. Prefer
 * the strategy that reveals the least while still leaving the field useful for
 * debugging. If nothing about the value needs to be readable, use {@link #FULL}.
 */
public enum MaskStrategy {

    /** Replace the entire value. The safe default, and the only correct choice for a CVV. */
    FULL,

    /**
     * Card number. Retains the 6-digit BIN and last 4, masking the middle.
     * PCI-DSS 3.3 permits at most first-six plus last-four to be displayed.
     */
    PAN,

    /** Retains the trailing 4 characters. For account numbers and similar identifiers. */
    LAST_FOUR,

    /** Masks the local part, keeps the domain: {@code a***@example.com}. */
    EMAIL,

    /** Retains the last 4 digits: {@code ******3210}. */
    MOBILE,

    /** UPI virtual payment address. Masks the handle, keeps the bank suffix: {@code a***@okhdfcbank}. */
    VPA,

    /**
     * Replaces the value with a truncated SHA-256 digest.
     *
     * <p>Use when you need to correlate occurrences of the same value across log
     * lines without disclosing it. Note this is a plain digest with no salt: it
     * is correlation-safe, not disclosure-safe against a small input space.
     */
    HASH
}
