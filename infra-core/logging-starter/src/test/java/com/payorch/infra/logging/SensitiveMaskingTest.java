package com.payorch.infra.logging;

import com.payorch.infra.logging.jackson.SensitiveJacksonModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-0 exit criterion: {@code @Sensitive} fields are masked in serialized
 * output.
 */
class SensitiveMaskingTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new SensitiveJacksonModule())
            .build();

    /** A record, because that is how the payment DTOs are declared. */
    record CardDetails(
            @Sensitive(MaskStrategy.PAN) String pan,
            @Sensitive String cvv,
            @Sensitive(MaskStrategy.EMAIL) String email,
            @Sensitive(MaskStrategy.MOBILE) String mobile,
            @Sensitive(MaskStrategy.VPA) String vpa,
            String bin,
            String last4) {
    }

    private static CardDetails sample() {
        return new CardDetails(
                "4242424242424242",
                "123",
                "umesh@example.com",
                "+919876543210",
                "umesh@okhdfcbank",
                "424242",
                "4242");
    }

    @Test
    @DisplayName("annotated fields are masked, unannotated fields are untouched")
    void masksAnnotatedFieldsOnly() {
        String json = mapper.writeValueAsString(sample());

        assertThat(json).contains("\"pan\":\"424242******4242\"");
        assertThat(json).contains("\"cvv\":\"****\"");
        assertThat(json).contains("\"email\":\"u***@example.com\"");
        assertThat(json).contains("\"mobile\":\"********3210\"");
        assertThat(json).contains("\"vpa\":\"u***@okhdfcbank\"");

        // bin and last4 are exactly what downstream is allowed to carry.
        assertThat(json).contains("\"bin\":\"424242\"");
        assertThat(json).contains("\"last4\":\"4242\"");
    }

    @Test
    @DisplayName("the raw PAN does not appear anywhere in the output")
    void rawPanNeverAppears() {
        String json = mapper.writeValueAsString(sample());

        assertThat(json).doesNotContain("4242424242424242");
        assertThat(json).doesNotContain("umesh@example.com");
        assertThat(json).doesNotContain("9876543210");
    }

    @Test
    @DisplayName("a PAN held as a number is still masked")
    void masksNonStringValues() {
        record NumericPan(@Sensitive(MaskStrategy.PAN) long pan) {
        }

        String json = mapper.writeValueAsString(new NumericPan(4242424242424242L));

        assertThat(json).contains("424242******4242");
        assertThat(json).doesNotContain("4242424242424242");
    }

    @Test
    @DisplayName("null sensitive fields serialize as null, not as a mask")
    void nullStaysNull() {
        String json = mapper.writeValueAsString(
                new CardDetails(null, null, null, null, null, "424242", "4242"));

        assertThat(json).contains("\"pan\":null");
        assertThat(json).contains("\"cvv\":null");
    }
}
