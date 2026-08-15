package com.payorch.infra.idempotency;

import java.util.regex.Pattern;

/**
 * Validation for a caller-supplied {@code Idempotency-Key}.
 *
 * <p>The key is caller-controlled input that becomes half of a database unique
 * constraint and a structured log field, so it is bounded and character-checked
 * before it reaches either.
 */
public final class IdempotencyKeys {

    /** Matches the column width in {@code V2__core_tables.sql}. */
    public static final int MAX_LENGTH = 255;

    /**
     * Printable ASCII without whitespace. Wide enough for a UUID, a ULID or a
     * caller's own order reference; narrow enough that the value cannot inject
     * a newline into a log line.
     */
    private static final Pattern SAFE = Pattern.compile("[!-~]{1,255}");

    private IdempotencyKeys() {
    }

    public static boolean isValid(String key) {
        return key != null && SAFE.matcher(key).matches();
    }
}
