package com.payorch.connector.provider;

import com.payorch.infra.logging.jackson.SensitiveJacksonModule;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the least obvious decision in this service: the outbound authorization
 * body must carry the <em>real</em> card number.
 *
 * <p>{@code @Sensitive} rewrites a property's serializer on every mapper the
 * logging starter is registered on, and Spring's HTTP message converter uses one
 * of those mappers. Annotating {@code ProviderRequest.pan} therefore looks like
 * a straightforward hardening change and is in fact an outage: the provider
 * receives {@code 424242******4242}, every authorization fails validation at the
 * acquirer, and nothing in any log says why - the masked value is exactly what
 * a reader would expect to see.
 *
 * <p>This test exists so that change fails here instead of in production. If it
 * ever goes red, the fix is to remove the annotation, not to relax the
 * assertion.
 */
class ProviderRequestSerializationTest {

    /** The same module the application registers on its {@code ObjectMapper}. */
    private final JsonMapper mapper = JsonMapper.builder().addModule(new SensitiveJacksonModule()).build();

    @Test
    void outboundBodyCarriesTheUnmaskedCardNumber() {
        var request = new MockPspAdapter.ProviderRequest(
                "attempt-1", 1000, "INR", "4242424242424242", 12, 2030);

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"pan\":\"4242424242424242\"");
        assertThat(json).doesNotContain("*");
    }

    /**
     * The other half of the protection: this record is never printed. A record's
     * generated {@code toString} includes every component, so any log line,
     * assertion message or debugger inspection that touched it would print the
     * card.
     */
    @Test
    void toStringDoesNotPrintTheCardNumber() {
        var request = new MockPspAdapter.ProviderRequest(
                "attempt-1", 1000, "INR", "4242424242424242", 12, 2030);

        assertThat(request.toString())
                .contains("attempt-1")
                .doesNotContain("4242424242424242");
    }
}
