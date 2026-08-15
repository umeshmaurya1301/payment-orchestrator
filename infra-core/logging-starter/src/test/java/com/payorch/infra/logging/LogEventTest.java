package com.payorch.infra.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogEventTest {

    @Test
    @DisplayName("allowlisted fields are accepted and carried through")
    void acceptsSchemaFields() {
        Object[] args = LogEvent.event()
                .with(LogFields.PAYMENT_ID, "pay_01H8")
                .with(LogFields.PSP_ID, "psp-a")
                .with(LogFields.LATENCY_MS, 142)
                .args();

        assertThat(args).hasSize(3);
        assertThat(args[0]).hasToString("paymentId=pay_01H8");
    }

    @Test
    @DisplayName("a field outside the schema is rejected, not silently logged")
    void rejectsUnknownFields() {
        // The failure mode this prevents: someone logs `cardNumber` because it
        // was convenient, and a denylist that never heard of it lets it through.
        assertThatThrownBy(() -> LogEvent.event().with("cardNumber", "4242424242424242"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }
}
