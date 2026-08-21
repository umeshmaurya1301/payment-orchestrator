package com.payorch.edge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.edge.orchestrator.OrchestratorClient;
import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
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
import org.springframework.mock.web.MockHttpServletResponse;
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
                    -- Phase 9b. The envelope. Nullable, because a row without a
                    -- kek_version is a pre-9b record read through the legacy cipher.
                    wrapped_dek  VARBINARY(64) NULL,
                    dek_iv       VARBINARY(12) NULL,
                    key_scope    VARCHAR(64)   NULL,
                    kek_version  VARCHAR(32)   NULL,
                    bin          CHAR(6)       NOT NULL,
                    last4        CHAR(4)       NOT NULL,
                    expiry_month TINYINT       NOT NULL,
                    expiry_year  SMALLINT      NOT NULL
                )
                """).update();
        vaultConnection.jdbc().sql("DELETE FROM token_vault").update();

        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM merchant_api_key").update();
        jdbc.sql("DELETE FROM merchant").update();
        seedMerchant(MERCHANT_ID, "Dev Merchant", API_KEY);

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
     *
     * <p><strong>Rewritten in 7a, because the original version asserted the
     * bug.</strong> It sent a different amount and a different card under the
     * same key and expected the first response back - which is exactly the
     * behaviour request fingerprinting exists to stop, written down as a
     * requirement. The replay it was really checking is this one: the same
     * request, sent twice, because that is the only case where returning the
     * first answer is correct. The other case now has its own test, and it
     * expects a 422.
     */
    @Test
    void replayingAKeyReturnsAByteIdenticalBody() throws Exception {
        byte[] first = mvc.perform(authenticated(create("key-replay")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        // The SAME request, resent - a merchant retrying after a timeout, which
        // is the case idempotency keys are for. If the guard were re-running the
        // work, the id in this body would differ.
        byte[] replayed = mvc.perform(authenticated(create("key-replay")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(replayed).isEqualTo(first);
        assertThat(orchestrator.requests)
                .as("the downstream call must have happened exactly once")
                .hasSize(1);

        Long records = jdbc.sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single();
        assertThat(records).isEqualTo(1);
    }

    /**
     * Phase 7a. THE BUG THE OLD REPLAY TEST ENCODED.
     *
     * <p>Same key, different amount. Before fingerprinting, this returned the
     * stored 201 for the first payment: the merchant asks to charge 9,999 minor
     * units, receives a success for 1,000, and nothing anywhere records that the
     * second request was never performed. A 422 costs them an error; a replay
     * costs them a wrong answer they will act on.
     */
    @Test
    void reusingAKeyForADifferentAmountIsRejected() throws Exception {
        mvc.perform(authenticated(create("key-reuse"))).andExpect(status().isCreated());

        mvc.perform(authenticated(create("key-reuse", PAN, 9999)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("idempotency_key_reused"));

        assertThat(orchestrator.requests)
                .as("a rejected reuse must not reach the orchestrator")
                .hasSize(1);
    }

    /** Same key, same amount, different card. Also a different request. */
    @Test
    void reusingAKeyForADifferentCardIsRejected() throws Exception {
        mvc.perform(authenticated(create("key-card"))).andExpect(status().isCreated());

        mvc.perform(authenticated(create("key-card", "5555555555554444", 1000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("idempotency_key_reused"));
    }

    /**
     * The 422 body must not describe either request.
     *
     * <p>The two bodies being compared contain card numbers, and a diff would be
     * the most useful thing a developer could read and also the shortest route
     * from a PAN into whatever the client of the merchant logs when it gets a
     * 422.
     */
    @Test
    void theRejectionSaysNothingAboutEitherRequest() throws Exception {
        mvc.perform(authenticated(create("key-quiet"))).andExpect(status().isCreated());

        String body = mvc.perform(authenticated(create("key-quiet", "5555555555554444", 9999)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("5555555555554444")
                .doesNotContain(PAN)
                .doesNotContain("9999");
    }

    /**
     * Formatting is not content. A client that reorders its JSON fields or adds
     * whitespace has not changed what it is asking for, and answering 422 to
     * that retry would leave the merchant holding a key they cannot use and
     * unable to tell whether the payment exists.
     *
     * <p>This is why the fingerprint is over named fields rather than over the
     * raw body - the opposite of the choice {@code ReplayableResponse} makes for
     * the response, and for the opposite reason.
     */
    @Test
    void aReorderedButIdenticalRequestStillReplays() throws Exception {
        byte[] first = mvc.perform(authenticated(create("key-format")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        String reordered = """
                {"currency":"INR",
                 "card":{"expiryYear":2030,"number":"%s","cvv":"123","expiryMonth":12},
                 "merchantReference":"order-1",   "amountMinor":1000}""".formatted(PAN);

        byte[] replayed = mvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", "key-format")
                        .header(ApiKeyAuthFilter.HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reordered))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(replayed).isEqualTo(first);
    }

    /**
     * The CVV is deliberately not fingerprinted. It never leaves
     * {@code EdgeApi.Card} - not stored, not hashed, not forwarded - and putting
     * it in the material would make the stored fingerprint a derivative of it,
     * which is the thing phase 1 promised not to do. The cost is this: a retry
     * differing only in CVV replays rather than 422ing, and that is the right
     * trade, because a changed CVV with an identical card and amount is not a
     * different payment.
     */
    @Test
    void aChangedCvvIsNotADifferentRequest() throws Exception {
        byte[] first = mvc.perform(authenticated(create("key-cvv")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        String differentCvv = """
                {"amountMinor":1000,"currency":"INR","merchantReference":"order-1",
                 "card":{"number":"%s","expiryMonth":12,"expiryYear":2030,"cvv":"999"}}"""
                .formatted(PAN);

        byte[] replayed = mvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", "key-cvv")
                        .header(ApiKeyAuthFilter.HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(differentCvv))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(replayed).isEqualTo(first);
    }

    /**
     * Phase 7b. THE EXIT CRITERION, at the HTTP boundary.
     *
     * <p>Concurrent requests sharing a key produce exactly one payment and one
     * response, replayed to everybody. Before 7b the losers received 409s: a
     * status that tells the caller nothing they can act on, because the payment
     * may or may not be about to exist, and that every one of them will retry
     * into the request that has not finished yet.
     *
     * <p><strong>Genuinely concurrent, which is the whole point.</strong> Phase
     * 7's trap list names the alternative: a loop of sequential requests all
     * take the replay path and prove nothing, because the window that matters is
     * the one where the winner has claimed and not yet completed.
     *
     * <p>Sixteen rather than the criterion's hundred, and the reason is the H2
     * connection pool rather than a lack of ambition: every waiter polls through
     * a REQUIRES_NEW transaction, so a hundred of them against a test-sized pool
     * would be measuring pool starvation - which is a real phenomenon, is the
     * headline of phase 7's later half, and is not what this test is about. The
     * hundred-thread version lives in {@code IdempotencyGuardTest}, where there
     * is no pool in the way.
     */
    @Test
    void concurrentRequestsWithOneKeyProduceOnePaymentAndReplaysForTheRest() throws Exception {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(authenticated(create("key-concurrent")))
                            .andReturn().getResponse();
                }));
            }
            start.countDown();

            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> result : results) {
                responses.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses)
                    .as("every caller must get the created payment, not a 409")
                    .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));

            byte[] first = responses.get(0).getContentAsByteArray();
            assertThat(responses)
                    .as("and it must be the same payment, byte for byte")
                    .allSatisfy(r -> assertThat(r.getContentAsByteArray()).isEqualTo(first));
        }

        assertThat(orchestrator.requests)
                .as("exactly one payment may have been created downstream")
                .hasSize(1);

        Long records = jdbc.sql(
                        "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?")
                .param("key-concurrent")
                .query(Long.class)
                .single();
        assertThat(records).isEqualTo(1);
    }

    /** The fingerprint is stored with the claim, not computed on read. */
    @Test
    void theClaimStoresItsFingerprint() throws Exception {
        mvc.perform(authenticated(create("key-fp"))).andExpect(status().isCreated());

        String fingerprint = jdbc.sql(
                        "SELECT request_fingerprint FROM idempotency_record WHERE idempotency_key = ?")
                .param("key-fp")
                .query(String.class)
                .single();

        assertThat(fingerprint)
                .as("64 lowercase hex characters of HMAC-SHA256")
                .matches("[0-9a-f]{64}");
        assertThat(fingerprint)
                .as("a keyed fingerprint must not be a readable derivative of the card")
                .doesNotContain(PAN.substring(0, 6));
    }

    /**
     * A merchant and one ACTIVE key for it. Since 9b those are two rows: the
     * credential lives in {@code merchant_api_key} so that a merchant can hold
     * several during a rotation.
     */
    private void seedMerchant(UUID merchantId, String name, String apiKey) {
        jdbc.sql("""
                INSERT INTO merchant (id, name, status, created_at)
                VALUES (?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(merchantId))
                .param(name)
                .param("ACTIVE")
                .param(Timestamp.from(Instant.now()))
                .update();
        seedKey(merchantId, apiKey, "original", "ACTIVE", null);
    }

    private void seedKey(UUID merchantId, String apiKey, String label,
                         String status, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO merchant_api_key
                    (id, merchant_id, api_key_hash, label, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(Uuid7.generate()))
                .param(Uuid7.toBytes(merchantId))
                .param(ApiKeyAuthFilter.sha256Hex(apiKey))
                .param(label)
                .param(status)
                .param(Timestamp.from(Instant.now()))
                .param(expiresAt == null ? null : Timestamp.from(expiresAt))
                .update();
    }

    @Test
    void twoMerchantsMayUseTheSameKey() throws Exception {
        UUID other = UUID.fromString("0192abcd-0000-7000-8000-0000000000ff");
        String otherKey = "pk_test_other_merchant";
        seedMerchant(other, "Other Merchant", otherKey);

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

    /**
     * Implements the interface rather than extending the REST client.
     *
     * <p>Before 9a made this hop selectable, {@code OrchestratorClient} was a
     * class and this stub extended it — which meant constructing a real
     * {@code RestClient} pointed at {@code localhost:1} and inheriting three
     * methods it then overrode. The comment justifying that constructor said it
     * made a signature change fail here rather than in a container, and the
     * interface does that better: a method added to the contract fails to
     * compile here, with no pretend transport involved.
     */
    static class StubOrchestrator implements OrchestratorClient {

        final List<CreatePaymentRequest> requests = new ArrayList<>();
        final List<PaymentResponse> created = new ArrayList<>();
        boolean unavailable;
        String merchantIdOverride;

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

        /**
         * Not exercised by this class - capture has its own tests - but the
         * interface has three methods and a stub that silently did nothing for
         * one of them would make a future capture test pass for the wrong
         * reason.
         */
        @Override
        public PaymentResponse capture(String paymentId) {
            throw new UnsupportedOperationException("capture is not stubbed here");
        }

        void reset() {
            requests.clear();
            created.clear();
            unavailable = false;
            merchantIdOverride = null;
        }
    }
}
