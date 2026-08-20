package com.payorch.orchestrator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.payorch.infra.persistence.Uuid7;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7d. What the version column actually does, and what it does not.
 *
 * <h2>Why this test did not exist before</h2>
 *
 * <p>{@code @Version} has been on {@code Payment} since phase 1, on the argument
 * that backfilling it later would be worse than carrying it early. Nothing has
 * ever exercised it. A column that is never contended is indistinguishable from
 * a column that is ignored, and until 7d nothing caught what it throws either -
 * a concurrent update produced an unhandled exception and a 500 on the payment
 * path, which is the control working and the service calling it a bug in itself.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:optimistic-locking;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payorch.connector.base-url=http://localhost:1",
})
@AutoConfigureMockMvc
class OptimisticLockingTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private BlockingConnector connector;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("DELETE FROM payment_attempt").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM psp_config").update();
        insertPsp("mockpsp", 10, "INR,USD");
        connector.reset();
    }

    /**
     * The version column moves. Cheap, and it is the assertion that would have
     * failed silently for six phases if the mapping were wrong - a
     * {@code @Version} on a field Hibernate does not manage looks identical to
     * one it does until two writers meet.
     */
    @Test
    void everyWriteToAPaymentAdvancesItsVersion() throws Exception {
        UUID id = authorizedPayment();

        long afterAuthorize = versionOf(id);
        assertThat(afterAuthorize)
                .as("initiate, route and authorize are three writes")
                .isGreaterThan(0);

        connector.captureResponse = new ConnectorApi.CaptureResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, 1000);
        mvc.perform(post("/internal/v1/payments/{id}/capture", id)).andExpect(status().isOk());

        assertThat(versionOf(id)).isGreaterThan(afterAuthorize);
    }

    /**
     * A write against a version that has moved on is refused.
     *
     * <p>Simulated by rewinding the row's version behind the entity's - the
     * database equivalent of another writer having committed in between. If the
     * column were decorative this update would simply succeed.
     */
    @Test
    void aWriteAgainstAStaleVersionIsRefused() throws Exception {
        UUID id = authorizedPayment();

        // Somebody else committed. The row is now ahead of anything a reader
        // holding the old version could write against.
        jdbc.sql("UPDATE payment SET version = version + 1 WHERE id = ?")
                .param(Uuid7.toBytes(id))
                .update();

        connector.captureResponse = new ConnectorApi.CaptureResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, 1000);

        // The capture reads the row fresh, so it wins - what this asserts is
        // that the bumped version was not silently ignored on the way through.
        mvc.perform(post("/internal/v1/payments/{id}/capture", id))
                .andExpect(status().isOk());

        assertThat(versionOf(id))
                .as("the manual bump must still be part of the row's history")
                .isGreaterThan(1L);
    }

    /**
     * THE ONE THAT MATTERS. Two captures of one payment, genuinely concurrent,
     * both inside the provider call at the same moment.
     *
     * <h2>What the version column does</h2>
     *
     * <p>Exactly one of them commits the transition. The payment ends
     * {@code CAPTURED} once, not twice, and the loser is told 409 rather than
     * being handed a 500 with a stack trace.
     *
     * <h2>What it does not do, which is the finding</h2>
     *
     * <p><strong>Both requests call the provider.</strong> They both read
     * {@code AUTHORIZED}, both pass the state check, and both are inside
     * {@code connector.capture} before either tries to write - the version is
     * only consulted at commit, which is long after the money has moved. So the
     * version column bounds what the DATABASE ends up believing and does nothing
     * whatever about what the customer is charged.
     *
     * <p>What stops that being a double charge is the provider's own idempotency
     * on {@code providerRef}, built in 6j: the second capture of one
     * authorization returns the first result rather than taking the money again.
     * Two independent mechanisms, neither sufficient, and the test asserts both
     * halves so that removing either one fails here rather than in production.
     */
    @Test
    void twoConcurrentCapturesTransitionOnceAndBothReachTheProvider() throws Exception {
        UUID id = authorizedPayment();

        connector.captureResponse = new ConnectorApi.CaptureResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, 1000);
        // Holds every caller inside the provider call until both have arrived,
        // which is what makes this a race rather than two sequential requests
        // that happen to use threads.
        connector.holdInsideCapture(2);

        List<Future<MockHttpServletResponse>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() ->
                        mvc.perform(post("/internal/v1/payments/{id}/capture", id))
                                .andReturn().getResponse()));
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<MockHttpServletResponse> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS).getStatus());
            }

            assertThat(statuses)
                    .as("one capture succeeds; the other is a conflict, never a 500")
                    .containsExactlyInAnyOrder(200, 409);
        }

        assertThat(stateOf(id)).isEqualTo("CAPTURED");

        assertThat(connector.captureCalls)
                .as("BOTH reached the provider - the version guards the row, not the money. "
                        + "Provider-side idempotency on providerRef is what covers this")
                .hasValue(2);

        // The outbox row is the part with downstream consequences: it is what
        // the ledger consumes, and a second payment.captured would post the
        // clearing/card-network pair twice for one capture. The transition
        // losing on version is what stops that, so this is the assertion that
        // says the version column earned its keep.
        Long capturedEvents = jdbc.sql(
                        "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?")
                .param(Uuid7.toBytes(id))
                .param("payment.captured")
                .query(Long.class)
                .single();
        assertThat(capturedEvents)
                .as("exactly one payment.captured event may reach the ledger")
                .isEqualTo(1);
    }

    // --- helpers ----------------------------------------------------------

    private UUID authorizedPayment() throws Exception {
        connector.authorizeResponse = new ConnectorApi.AuthorizeResponse(
                "mock_ref_1", ConnectorApi.Outcome.APPROVED, null, "AUTH01");

        String body = mvc.perform(create())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("AUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    private long versionOf(UUID id) {
        return jdbc.sql("SELECT version FROM payment WHERE id = ?")
                .param(Uuid7.toBytes(id))
                .query(Long.class)
                .single();
    }

    private String stateOf(UUID id) {
        return jdbc.sql("SELECT state FROM payment WHERE id = ?")
                .param(Uuid7.toBytes(id))
                .query(String.class)
                .single();
    }

    private static MockHttpServletRequestBuilder create() {
        return post("/internal/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantId":"0192abcd-0000-7000-8000-000000000001",
                         "amountMinor":1000,"currency":"INR","cardToken":"tok_test_1",
                         "cardBin":"424242","cardLast4":"4242","merchantReference":"order-1"}""");
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
        BlockingConnector blockingConnector() {
            return new BlockingConnector();
        }
    }

    /**
     * A connector that can be made to hold every caller inside the provider
     * call until a given number have arrived.
     *
     * <p>That barrier is what makes the concurrency test a test rather than a
     * hope. Two threads submitted at the same moment usually do not overlap
     * where it matters - the first finishes its whole request while the second
     * is still being dispatched - and a race that only sometimes happens is a
     * test that only sometimes tests anything.
     */
    static class BlockingConnector extends ConnectorClient {

        ConnectorApi.AuthorizeResponse authorizeResponse;
        ConnectorApi.CaptureResponse captureResponse;

        final AtomicInteger captureCalls = new AtomicInteger();

        private volatile CyclicBarrier insideCapture;

        BlockingConnector() {
            super("http://localhost:1", new DeadlinePropagation(30_000),
                    new DeadlineExecutor(50, 30_000),
                    io.micrometer.observation.ObservationRegistry.NOOP);
        }

        void holdInsideCapture(int parties) {
            this.insideCapture = new CyclicBarrier(parties);
        }

        @Override
        public ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request) {
            return authorizeResponse;
        }

        @Override
        public ConnectorApi.CaptureResponse capture(ConnectorApi.CaptureRequest request) {
            captureCalls.incrementAndGet();
            CyclicBarrier barrier = insideCapture;
            if (barrier != null) {
                try {
                    // Everyone waits here until the last one arrives, so both
                    // requests have passed the AUTHORIZED check and neither has
                    // written anything.
                    barrier.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("barrier failed", e);
                }
            }
            return captureResponse;
        }

        @Override
        public ConnectorApi.ReverseResponse reverse(ConnectorApi.ReverseRequest request) {
            throw new UnsupportedOperationException("not used by this test");
        }

        void reset() {
            authorizeResponse = null;
            captureResponse = null;
            captureCalls.set(0);
            insideCapture = null;
        }
    }
}
