package com.payorch.infra.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeysTest {

    @Test
    void acceptsTheFormsCallersActuallySend() {
        assertThat(IdempotencyKeys.isValid("0192abcd-0000-7000-8000-000000000001")).isTrue();
        assertThat(IdempotencyKeys.isValid("order_2026-08-15_00042")).isTrue();
        assertThat(IdempotencyKeys.isValid("a")).isTrue();
    }

    @Test
    void rejectsAbsentOrEmptyKeys() {
        assertThat(IdempotencyKeys.isValid(null)).isFalse();
        assertThat(IdempotencyKeys.isValid("")).isFalse();
    }

    /**
     * The key is written into a structured log field. A newline in it is a log
     * injection, and the check has to run before the value gets anywhere near
     * the logger.
     */
    @Test
    void rejectsWhitespaceAndControlCharacters() {
        assertThat(IdempotencyKeys.isValid("has space")).isFalse();
        assertThat(IdempotencyKeys.isValid("has\nnewline")).isFalse();
        assertThat(IdempotencyKeys.isValid("has\ttab")).isFalse();
    }

    @Test
    void rejectsKeysWiderThanTheColumn() {
        assertThat(IdempotencyKeys.isValid("k".repeat(IdempotencyKeys.MAX_LENGTH))).isTrue();
        assertThat(IdempotencyKeys.isValid("k".repeat(IdempotencyKeys.MAX_LENGTH + 1))).isFalse();
    }
}
