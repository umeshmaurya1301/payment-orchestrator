package com.payorch.orchestrator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.orchestrator.connector.ConnectorApi;
import com.payorch.orchestrator.connector.ConnectorClient;
import com.payorch.orchestrator.domain.PaymentState;
import com.payorch.orchestrator.saga.CompensationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6k. A capture, undone.
 *
 * <p>Driven through {@link PaymentService} rather than over HTTP, because
 * there is no HTTP endpoint for this and there should not be. A compensation is
 * not something a merchant asks for - it is the system resolving a disagreement
 * between two of its own services - and giving it a URL would make "reverse this
 * capture" a request anybody could make.
 *
 * <p>Schema note as in {@code PaymentFlowTest}: Hibernate generates the schema
 * here rather than Flyway, because the migrations are MySQL-specific and running
 * them against H2 would either fail or pass against a schema production does not
 * use.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:compensating-reversal;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payorch.connector.base-url=http://localhost:1",
})
@AutoConfigureMockMvc
class CompensatingReversalTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PaymentService payments;

    @Autowired
    private StubConnector connector;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("DELETE FROM payment_attempt").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM psp_config").update();
        insertPsp("mockpsp", 10, "INR,USD");
        connector.reset();
    }

    /** Authorize and capture a payment, the way the compensation will find it. */
    private UUID capturedPayment() throws Exception {
        connector.authorizeResponse = new ConnectorApi.AuthorizeResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, "AUTH01");

        String body = mvc.perform(create())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("AUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));

        connector.captureResponse = new ConnectorApi.CaptureResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, 4200);
        mvc.perform(post("/internal/v1/payments/{id}/capture", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CAPTURED"));

        return id;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create() {
        return post("/internal/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantId":"0192abcd-0000-7000-8000-000000000001",
                         "amountMinor":4200,"currency":"INR","cardToken":"tok_test_1",
                         "cardBin":"424242","cardLast4":"4242","merchantReference":"order-1"}""");
    }

    private String stateOf(UUID id) {
        return jdbc.sql("SELECT state FROM payment WHERE id = ?")
                .param(Uuid7.toBytes(id))
                .query(String.class)
                .single();
    }

    /** The path the whole phase exists for. */
    @Test
    void aCompensationTakesTheMoneyBackAndMovesThePaymentToReversed() throws Exception {
        UUID id = capturedPayment();
        connector.reverseResponse = new ConnectorApi.ReverseResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, 4200);

        assertThat(payments.reverseCapture(id, CompensationMessage.class.getSimpleName()))
                .isEqualTo(PaymentService.ReversalOutcome.REVERSED);

        assertThat(connector.lastReverse).isNotNull();
        assertThat(connector.lastReverse.providerRef())
                .as("the reversal must carry the same provider reference the capture used")
                .isEqualTo("mock_ref_1");
        assertThat(stateOf(id)).isEqualTo("REVERSED");
    }

    /**
     * IDEMPOTENT, and it must be. The compensation arrives on an at-least-once
     * topic like everything else in this system, so this method WILL be called
     * twice for one dead-lettered capture - and the second call must not reach
     * the provider at all.
     */
    @Test
    void aRedeliveredCompensationDoesNotTouchTheProviderASecondTime() throws Exception {
        UUID id = capturedPayment();
        connector.reverseResponse = new ConnectorApi.ReverseResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, 4200);

        payments.reverseCapture(id, "ledger_dead_lettered");
        connector.lastReverse = null;

        assertThat(payments.reverseCapture(id, "ledger_dead_lettered"))
                .isEqualTo(PaymentService.ReversalOutcome.ALREADY_REVERSED);
        assertThat(connector.lastReverse)
                .as("a redelivery must be answered from state, not by calling the provider")
                .isNull();
    }

    /**
     * THE BENIGN RACE, and the reason the outcome is not simply a boolean.
     *
     * <p>The commonest way to arrive here is not a bug: the ledger dead-lettered
     * a capture, somebody replayed the DLQ, the ledger posted it properly, and
     * the compensation turned up after the problem had already been fixed.
     * Reversing on that basis would take money back from a payment whose books
     * are now correct - the compensation causing the damage it exists to
     * prevent.
     *
     * <p>Approximated here by a payment that was never captured, which is the
     * same condition the guard actually tests.
     */
    @Test
    void aCompensationForAPaymentThatIsNoLongerCapturedDoesNothing() throws Exception {
        connector.authorizeResponse = new ConnectorApi.AuthorizeResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, "AUTH01");

        String body = mvc.perform(create())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));

        assertThat(payments.reverseCapture(id, "ledger_dead_lettered"))
                .isEqualTo(PaymentService.ReversalOutcome.NOT_CAPTURED);

        assertThat(connector.lastReverse).isNull();
        assertThat(stateOf(id))
                .as("a payment that was never captured must be left exactly as it was")
                .isEqualTo("AUTHORIZED");
    }

    /**
     * A refused compensation THROWS, and that is the design.
     *
     * <p>Returning an outcome would let the consumer commit its offset on a
     * disagreement about real money that nobody has resolved. Throwing puts the
     * record on the error handler's retry and then into
     * {@code payment.compensation.dlq}, which is a queue somebody reads -
     * exactly where an unresolved dispute about money belongs.
     */
    @Test
    void aProviderThatRefusesToReverseThrowsRatherThanReportingSuccess() throws Exception {
        UUID id = capturedPayment();
        connector.reverseResponse = new ConnectorApi.ReverseResponse(
                "mock_ref_1", ConnectorApi.Outcome.DECLINED, "already_settled", 0);

        assertThatThrownBy(() -> payments.reverseCapture(id, "ledger_dead_lettered"))
                .isInstanceOf(PaymentService.ReversalRefusedException.class)
                .hasMessageContaining("already_settled");

        assertThat(stateOf(id))
                .as("a payment the provider would not reverse is still captured, and must say so")
                .isEqualTo("CAPTURED");
    }

    /**
     * THE TOKENIZATION BOUNDARY, re-pinned across all three provider operations.
     *
     * <p>Only {@code authorize} ever handles a detokenized card. Capture was
     * pinned in phase 6j and reversal is pinned here, because the way this
     * boundary erodes is not a decision - it is a new operation quietly given a
     * card field because the record next to it had one.
     */
    @Test
    void aReversalNeverCarriesCardData() {
        assertThat(ConnectorApi.ReverseRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("providerRef", "pspId");
    }

    /** A compensation for a payment this service has never heard of is a bug, not a no-op. */
    @Test
    void aCompensationForAnUnknownPaymentFailsLoudly() {
        assertThatThrownBy(() -> payments.reverseCapture(UUID.randomUUID(), "ledger_dead_lettered"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no record of");
    }

    private void insertPsp(String pspId, int priority, String currencies) {
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

        @Bean
        @Primary
        StubConnector stubConnector() {
            return new StubConnector();
        }
    }

    /**
     * Overrides all three operations.
     *
     * <p>The base class is the real {@link ConnectorClient}, constructed the way
     * production constructs it, so a signature change breaks here rather than in
     * a container. Note that {@code reverse} has to be overridden even for the
     * tests that never expect it to be called: the real one would open an HTTP
     * connection to localhost:1, and "the provider was not called" would then be
     * indistinguishable from "the provider was called and the connection was
     * refused".
     */
    static class StubConnector implements ConnectorClient {

        ConnectorApi.AuthorizeResponse authorizeResponse;
        ConnectorApi.CaptureResponse captureResponse;
        ConnectorApi.ReverseResponse reverseResponse;
        ConnectorApi.ReverseRequest lastReverse;


        @Override
        public ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request) {
            return authorizeResponse;
        }

        @Override
        public ConnectorApi.CaptureResponse capture(ConnectorApi.CaptureRequest request) {
            return captureResponse;
        }

        @Override
        public ConnectorApi.ReverseResponse reverse(ConnectorApi.ReverseRequest request) {
            lastReverse = request;
            return reverseResponse;
        }

        void reset() {
            authorizeResponse = null;
            captureResponse = null;
            reverseResponse = null;
            lastReverse = null;
        }

        @Override
        public ConnectorApi.LookupResponse lookup(ConnectorApi.LookupRequest request) {
            throw new UnsupportedOperationException("this test never looks up");
        }
    }
}
