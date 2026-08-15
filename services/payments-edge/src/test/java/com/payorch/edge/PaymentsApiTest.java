package com.payorch.edge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.edge.orchestrator.OrchestratorClient;
import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.tokenization.VaultConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The merchant-facing API: authentication, tokenization on arrival, and
 * byte-identical idempotent replay.
 *
 * <p>The orchestrator is stubbed. What is being verified here is everything that
 * only happens at the edge - the three services together are covered by the
 * cold-start smoke run in the phase-1 exit criteria.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:edge-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payorch.orchestrator.base-url=http://localhost:1",
        "payorch.vault.datasource.url=jdbc:h2:mem:edge-vault;DB_CLOSE_DELAY=-1",
        "payorch.vault.datasource.username=sa",
        "payorch.vault.datasource.password=",
        "payorch.vault.verify-on-startup=false",
})
@AutoConfigureMockMvc
class PaymentsApiTest {

    private static final String PAN = "4242424242424242";
    private static final String API_KEY = "pk_test_dev_merchant_key";
    private static final UUID MERCHANT_ID = UUID.fromString("0192abcd-0000-7000-8000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private VaultConnection vaultConnection;

    @Autowired
    private StubOrchestrator orchestrator;

    @BeforeEach
    void resetState() {
        vaultConnection.jdbc().sql("""
                CREATE TABLE IF NOT EXISTS token_vault (
                    token        VARCHAR(48)   NOT NULL PRIMARY KEY,
                    pan_iv       VARBINARY(12) NOT NULL,
                    pan_cipher   VARBINARY(64) NOT NULL,
                    bin          CHAR(6)       NOT NULL,
                    last4        CHAR(4)       NOT NULL,
                    expiry_month TINYINT       NOT NULL,
                    expiry_year  SMALLINT      NOT NULL
                )
                """).update();
        vaultConnection.jdbc().sql("DELETE FROM token_vault").update();

        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM merchant").update();
        jdbc.sql("""
                INSERT INTO merchant (id, name, api_key_hash, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(MERCHANT_ID))
                .param("Dev Merchant")
                .param(ApiKeyAuthFilter.sha256Hex(API_KEY))
                .param("ACTIVE")
                .param(Timestamp.from(Instant.now()))
                .update();

        orchestrator.reset();
    }

    // --- authentication ---------------------------------------------------

    @Test
    void aRequestWithoutAnApiKeyIsRejected() throws Exception {
        mvc.perform(create("key-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("missing_api_key"));
    }

    @Test
    void anUnknownApiKeyIsRejected() throws Exception {
        mvc.perform(create("key-1").header(ApiKeyAuthFilter.HEADER, "pk_test_wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("invalid_api_key"));
    }

    @Test
    void actuatorIsNotBehindTheApiKey() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // --- tokenization -----------------------------------------------------

    /**
     * The tokenization boundary, asserted rather than described: the request
     * carried a card number, and what left this service was a token.
     */
    @Test
    void theCardIsTokenizedBeforeAnythingElseSeesIt() throws Exception {
        mvc.perform(authenticated(create("key-1"))).andExpect(status().isCreated());

        assertThat(orchestrator.requests).hasSize(1);
        var forwarded = orchestrator.requests.getFirst();

        assertThat(forwarded.cardToken()).startsWith("tok_");
        assertThat(forwarded.cardBin()).isEqualTo("424242");
        assertThat(forwarded.cardLast4()).isEqualTo("4242");

        // The strongest form of the claim: the serialized downstream request has
        // no card number in it anywhere.
        assertThat(forwarded.toString()).doesNotContain(PAN);
    }

    @Test
    void theVaultHoldsTheCardAndTheResponseDoesNot() throws Exception {
        String body = mvc.perform(authenticated(create("key-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PAN).contains("\"cardLast4\":\"4242\"");

        Long vaultRows = vaultConnection.jdbc()
                .sql("SELECT COUNT(*) FROM token_vault").query(Long.class).single();
        assertThat(vaultRows).isEqualTo(1);
    }

    /**
     * The CVV must reach nothing. Not the vault, not the downstream request, not
     * the response.
     */
    @Test
    void theCvvIsDiscardedAtTheEdge() throws Exception {
        String body = mvc.perform(authenticated(create("key-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("123");
        assertThat(orchestrator.requests.getFirst().toString()).doesNotContain("123");

        var vaultColumns = vaultConnection.jdbc().sql("""
                SELECT LOWER(column_name) FROM information_schema.columns
                WHERE LOWER(table_name) = 'token_vault'
                """).query(String.class).list();
        assertThat(vaultColumns).noneMatch(c -> c.contains("cvv") || c.contains("cvc"));
    }

    @Test
    void aCardThatFailsLuhnIsRejectedWithoutEchoingIt() throws Exception {
        String body = mvc.perform(authenticated(create("key-1", "4242424242424241", 1000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_card"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("4242424242424241");
    }

    // --- idempotency ------------------------------------------------------

    @Test
    void anIdempotencyKeyIsRequired() throws Exception {
        mvc.perform(post("/v1/payments")
                        .header(ApiKeyAuthFilter.HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(PAN, 1000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_idempotency_key"));
    }

    /**
     * Phase 1 exit criterion 4: the replayed body is byte-identical and exactly
     * one payment exists.
     */
    @Test
    void replayingAKeyReturnsAByteIdenticalBody() throws Exception {
        byte[] first = mvc.perform(authenticated(create("key-replay")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        // A different amount and a different card, under the same key. If the
        // guard were re-running the work, this response would differ.
        byte[] replayed = mvc.perform(authenticated(create("key-replay", "5555555555554444", 9999)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(replayed).isEqualTo(first);
        assertThat(orchestrator.requests)
                .as("the downstream call must have happened exactly once")
                .hasSize(1);

        Long records = jdbc.sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single();
        assertThat(records).isEqualTo(1);
    }

    @Test
    void twoMerchantsMayUseTheSameKey() throws Exception {
        UUID other = UUID.fromString("0192abcd-0000-7000-8000-0000000000ff");
        String otherKey = "pk_test_other_merchant";
        jdbc.sql("""
                INSERT INTO merchant (id, name, api_key_hash, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(other))
                .param("Other Merchant")
                .param(ApiKeyAuthFilter.sha256Hex(otherKey))
                .param("ACTIVE")
                .param(Timestamp.from(Instant.now()))
                .update();

        mvc.perform(authenticated(create("shared-key"))).andExpect(status().isCreated());
        mvc.perform(create("shared-key").header(ApiKeyAuthFilter.HEADER, otherKey))
                .andExpect(status().isCreated());

        assertThat(orchestrator.requests).hasSize(2);
    }

    /**
     * A failed attempt must not burn the key. Without the release in
     * {@code IdempotencyGuard}, the caller could never retry with the same key -
     * which is exactly what idempotency keys exist to make possible.
     */
    @Test
    void aFailedAttemptCanBeRetriedWithTheSameKey() throws Exception {
        orchestrator.unavailable = true;

        mvc.perform(authenticated(create("key-retry")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("orchestrator_unavailable"));

        orchestrator.unavailable = false;

        mvc.perform(authenticated(create("key-retry"))).andExpect(status().isCreated());
    }

    // --- retrieval --------------------------------------------------------

    @Test
    void aPaymentCanBeFetchedBack() throws Exception {
        String body = mvc.perform(authenticated(create("key-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(get("/v1/payments/{id}", id).header(ApiKeyAuthFilter.HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.state").value("AUTHORIZED"))
                .andExpect(jsonPath("$.cardLast4").value("4242"));
    }

    /**
     * A merchant must not be able to read another merchant's payment by
     * guessing an id, and the answer must be 404 rather than 403 - a 403 would
     * confirm the payment exists.
     */
    @Test
    void aPaymentBelongingToAnotherMerchantIsNotFound() throws Exception {
        orchestrator.merchantIdOverride = UUID.randomUUID().toString();

        String body = mvc.perform(authenticated(create("key-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(get("/v1/payments/{id}", id).header(ApiKeyAuthFilter.HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("payment_not_found"));
    }

    /**
     * The internal routing decision must not leak. Phase 5 changes the chosen
     * provider on every request, and that only stays possible if no merchant
     * ever saw which one was used.
     */
    @Test
    void theResponseHidesTheProvider() throws Exception {
        String body = mvc.perform(authenticated(create("key-1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("pspId").doesNotContain("mockpsp").doesNotContain("providerRef");
    }

    // --- helpers ----------------------------------------------------------

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder) {
        return builder.header(ApiKeyAuthFilter.HEADER, API_KEY);
    }

    private static MockHttpServletRequestBuilder create(String idempotencyKey) {
        return create(idempotencyKey, PAN, 1000);
    }

    private static MockHttpServletRequestBuilder create(String idempotencyKey, String pan, long amount) {
        return post("/v1/payments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(pan, amount));
    }

    private static String payload(String pan, long amount) {
        return """
                {"amountMinor":%d,"currency":"INR","merchantReference":"order-1",
                 "card":{"number":"%s","expiryMonth":12,"expiryYear":2030,"cvv":"123"}}"""
                .formatted(amount, pan);
    }

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        StubOrchestrator stubOrchestrator() {
            return new StubOrchestrator();
        }
    }

    static class StubOrchestrator extends OrchestratorClient {

        final List<CreatePaymentRequest> requests = new ArrayList<>();
        final List<PaymentResponse> created = new ArrayList<>();
        boolean unavailable;
        String merchantIdOverride;

        StubOrchestrator() {
            super("http://localhost:1");
        }

        @Override
        public PaymentResponse create(CreatePaymentRequest request) {
            if (unavailable) {
                throw new OrchestratorUnavailableException(null);
            }
            requests.add(request);

            PaymentResponse response = new PaymentResponse(
                    Uuid7.generate().toString(),
                    merchantIdOverride != null ? merchantIdOverride : request.merchantId(),
                    "AUTHORIZED",
                    request.amountMinor(),
                    request.currency(),
                    request.cardBin(),
                    request.cardLast4(),
                    "mockpsp",
                    "mock_ref_1",
                    null,
                    request.merchantReference(),
                    Instant.parse("2026-08-15T10:00:00Z"),
                    Instant.parse("2026-08-15T10:00:01Z"));
            created.add(response);
            return response;
        }

        @Override
        public Optional<PaymentResponse> find(String paymentId) {
            return created.stream().filter(p -> p.id().equals(paymentId)).findFirst();
        }

        void reset() {
            requests.clear();
            created.clear();
            unavailable = false;
            merchantIdOverride = null;
        }
    }
}
