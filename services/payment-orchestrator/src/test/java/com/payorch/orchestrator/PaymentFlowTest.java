package com.payorch.orchestrator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.resilience.deadline.DeadlineExceededException;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.orchestrator.connector.ConnectorApi;
import com.payorch.orchestrator.connector.ConnectorClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The state machine driven end to end, against a real persistence layer.
 *
 * <p><strong>Schema note.</strong> Hibernate generates the schema here instead
 * of Flyway running it. The phase-1 migrations are MySQL-specific -
 * {@code ENGINE = InnoDB}, {@code BINARY(16)}, {@code DATETIME(3)} - and running
 * them against H2 would either fail outright or, worse, pass against a schema
 * that is not the one production uses. The migrations are verified where they
 * actually run: against the MySQL container during {@code docker compose up},
 * with {@code ddl-auto: validate} on, which fails startup if the entities and
 * the migrations have drifted.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payorch.connector.base-url=http://localhost:1",
})
@AutoConfigureMockMvc
class PaymentFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StubConnector connector;

    /**
     * The context - and therefore the H2 database - is shared across every test
     * in this class, so each one starts by clearing what the last one wrote.
     * Several assertions below count rows, and stale rows would make them pass
     * or fail depending on execution order.
     */
    @BeforeEach
    void resetDatabase() {
        jdbc.sql("DELETE FROM payment_attempt").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM psp_config").update();
        insertPsp("mockpsp", 10, "INR,USD");
        connector.reset();
    }

    @Test
    void aPaymentReachesAuthorized() throws Exception {
        connector.response = new ConnectorApi.AuthorizeResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, "AUTH01");

        String body = mvc.perform(create(1000, "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("AUTHORIZED"))
                .andExpect(jsonPath("$.pspId").value("mockpsp"))
                .andExpect(jsonPath("$.providerRef").value("mock_ref_1"))
                .andReturn().getResponse().getContentAsString();

        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(get("/internal/v1/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUTHORIZED"))
                .andExpect(jsonPath("$.cardLast4").value("4242"));
    }

    /**
     * The provider's reference must be the attempt id, unchanged. It is the
     * provider's idempotency key, and phase 3's first retry is only safe because
     * a retry reuses it.
     */
    @Test
    void theProviderReferenceIsThePersistedAttemptId() throws Exception {
        connector.response = new ConnectorApi.AuthorizeResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, "AUTH01");

        mvc.perform(create(1000, "INR")).andExpect(status().isCreated());

        assertThat(connector.lastRequest).isNotNull();
        assertThat(Uuid7.parseOrNull(connector.lastRequest.reference()))
                .as("the reference must be a persisted attempt id, not an ad-hoc value")
                .isNotNull();

        Long attemptRows = jdbc.sql("SELECT COUNT(*) FROM payment_attempt").query(Long.class).single();
        assertThat(attemptRows).isEqualTo(1);
    }

    @Test
    void aDeclineIsFailedNotUnknown() throws Exception {
        connector.response = new ConnectorApi.AuthorizeResponse(
                "mock_ref_2", ConnectorApi.Outcome.DECLINED, "insufficient_funds", null);

        mvc.perform(create(1005, "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("insufficient_funds"));
    }

    /**
     * The transition the entire design exists for. No answer came back, so the
     * payment is UNKNOWN - the card may well have been charged, and calling this
     * FAILED is what invites a caller to retry into a double charge.
     */
    @Test
    void noAnswerFromTheConnectorIsUnknownNotFailed() throws Exception {
        connector.unavailable = true;

        mvc.perform(create(1000, "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.errorCode").value("connector_unavailable"));

        String outcome = jdbc.sql("SELECT outcome FROM payment_attempt").query(String.class).single();
        assertThat(outcome).isEqualTo("UNKNOWN");
    }

    /**
     * Nothing was contacted, so the card provably was not charged. That makes it
     * FAILED - an UNKNOWN here would send phase 8's poller hunting a provider
     * reference that was never issued.
     */
    @Test
    void anUnroutableCurrencyIsFailedAndPersisted() throws Exception {
        mvc.perform(create(1000, "GBP"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.pspId").doesNotExist());

        Long attempts = jdbc.sql("SELECT COUNT(*) FROM payment_attempt").query(Long.class).single();
        assertThat(attempts).as("no provider was contacted, so no attempt exists").isZero();

        String state = jdbc.sql("SELECT state FROM payment").query(String.class).single();
        assertThat(state)
                .as("the failure must be committed, not rolled back by the exception that reported it")
                .isEqualTo("FAILED");
    }

    /**
     * 3a. The budget ran out before anything was sent, so the card was
     * demonstrably not charged. That is FAILED, and a FAILED payment is one a
     * merchant may safely retry.
     */
    @Test
    void aDeadlineThatExpiredBeforeSendingIsFailed() throws Exception {
        connector.deadlineNotStarted = true;

        mvc.perform(create(1000, "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("deadline_exceeded"));

        String outcome = jdbc.sql("SELECT outcome FROM payment_attempt").query(String.class).single();
        assertThat(outcome).isEqualTo("FAILED");
    }

    /**
     * The same exception type, the opposite state - and this is exactly why
     * DeadlineExceededException carries `wasStarted` instead of being one
     * undifferentiated timeout. The request went out and was abandoned, so the
     * provider may already have authorized the card. Calling this FAILED would
     * invite the merchant to retry into a double charge.
     */
    @Test
    void aDeadlineThatExpiredAfterSendingIsUnknown() throws Exception {
        connector.deadlineAbandoned = true;

        mvc.perform(create(1000, "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.errorCode").value("deadline_abandoned"));

        String outcome = jdbc.sql("SELECT outcome FROM payment_attempt").query(String.class).single();
        assertThat(outcome).isEqualTo("UNKNOWN");
    }

    /**
     * The tokenization boundary, checked at the database rather than in a
     * comment: no table this service owns has anywhere to put a card number.
     */
    @Test
    void thePaymentRowHoldsNoCardNumber() throws Exception {
        connector.response = new ConnectorApi.AuthorizeResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, "AUTH01");

        mvc.perform(create(1000, "INR")).andExpect(status().isCreated());

        var columns = jdbc.sql("""
                SELECT LOWER(column_name) FROM information_schema.columns
                WHERE LOWER(table_name) = 'payment'
                """).query(String.class).list();

        assertThat(columns).contains("card_token", "card_bin", "card_last4");
        assertThat(columns).noneMatch(c -> c.contains("pan") || c.contains("cvv")
                || c.equals("card_number"));
    }

    @Test
    void anUnknownPaymentIdIsNotFound() throws Exception {
        mvc.perform(get("/internal/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("payment_not_found"));
    }

    @Test
    void aMalformedPaymentIdIsNotFoundRatherThanAServerError() throws Exception {
        mvc.perform(get("/internal/v1/payments/{id}", "not-a-uuid"))
                .andExpect(status().isNotFound());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
            long amountMinor, String currency) {
        return post("/internal/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantId":"0192abcd-0000-7000-8000-000000000001",
                         "amountMinor":%d,"currency":"%s","cardToken":"tok_test_1",
                         "cardBin":"424242","cardLast4":"4242","merchantReference":"order-1"}"""
                        .formatted(amountMinor, currency));
    }

    private void insertPsp(String pspId, int priority, String currencies) {
        // cost_bps is listed explicitly because the schema here comes from the
        // entities (ddl-auto: create-drop), so a NOT NULL column added in a
        // migration has no default to fall back on. The migration's DEFAULT 200
        // only applies to the real MySQL schema.
        jdbc.sql("""
                INSERT INTO psp_config
                    (id, psp_id, display_name, enabled, priority, supported_currencies,
                     cost_bps, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(Uuid7.toBytes(Uuid7.generate()))
                .param(pspId)
                .param(pspId)
                .param(true)
                .param(priority)
                .param(currencies)
                .param(200)
                .param(Timestamp.from(Instant.now()))
                .update();
    }

    @TestConfiguration
    static class Stubs {

        /**
         * {@code @Primary} rather than replacing the bean definition: the real
         * {@link ConnectorClient} still gets constructed, which keeps the
         * production wiring under test even though it is never called.
         */
        @Bean
        @Primary
        StubConnector stubConnector() {
            return new StubConnector();
        }
    }

    static class StubConnector extends ConnectorClient {

        ConnectorApi.AuthorizeResponse response;
        ConnectorApi.AuthorizeRequest lastRequest;
        boolean unavailable;
        boolean deadlineNotStarted;
        boolean deadlineAbandoned;

        StubConnector() {
            // Real collaborators even though authorize() is overridden: they are
            // cheap, and constructing the stub the way production constructs the
            // real client keeps this from compiling after a signature change
            // that would break production.
            super("http://localhost:1", new DeadlinePropagation(30_000), new DeadlineExecutor(50, 30_000),
                    // No tracing in a unit test, but the argument is still passed:
                    // constructing the stub the way production does is what makes a
                    // signature change fail here rather than in a container.
                    io.micrometer.observation.ObservationRegistry.NOOP);
        }

        @Override
        public ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request) {
            lastRequest = request;
            if (unavailable) {
                throw new ConnectorUnavailableException(null);
            }
            if (deadlineNotStarted) {
                throw DeadlineExceededException.notStarted("connector.authorize", 10, 50);
            }
            if (deadlineAbandoned) {
                throw DeadlineExceededException.abandoned("connector.authorize", 5_000);
            }
            return response;
        }

        void reset() {
            response = null;
            lastRequest = null;
            unavailable = false;
            deadlineNotStarted = false;
            deadlineAbandoned = false;
        }
    }
}
