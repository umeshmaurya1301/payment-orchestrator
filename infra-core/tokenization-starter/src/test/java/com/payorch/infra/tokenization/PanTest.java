package com.payorch.infra.tokenization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PanTest {

    @Test
    void extractsBinAndLastFour() {
        Pan pan = Pan.of("4242424242424242");

        assertThat(pan.bin()).isEqualTo("424242");
        assertThat(pan.last4()).isEqualTo("4242");
    }

    @Test
    void toleratesFormattingCharacters() {
        assertThat(Pan.of("4242 4242-4242 4242")).isEqualTo(new Pan("424242", "4242"));
        assertThat(Pan.normalise("4242 4242-4242 4242")).isEqualTo("4242424242424242");
    }

    @Test
    void rejectsANumberThatFailsLuhn() {
        assertThatThrownBy(() -> Pan.of("4242424242424241"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Luhn");
    }

    @Test
    void rejectsImplausibleLengths() {
        assertThatThrownBy(() -> Pan.of("42424242")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pan.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pan.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The rejection path is the one most likely to leak. A validation error
     * travels into a log line and often into an error response, and it must not
     * carry the number that was rejected.
     */
    @Test
    void rejectionMessagesCarryNoCardData() {
        assertThatThrownBy(() -> Pan.of("4242424242424241"))
                .hasMessageNotContaining("4242");
    }
}
