package com.payorch.edge.orchestrator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.infra.resilience.deadline.DeadlineExecutor;
import org.infra.resilience.deadline.DeadlinePropagation;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code RestOrchestratorClient.watch}, and specifically its private list of
 * which states end a watch. Phase 9a item 4.
 *
 * <h2>The bug this class exists to keep fixed</h2>
 *
 * <p>{@code TERMINAL} listed {@code CAPTURED}. It is not terminal —
 * {@code PaymentTransitions} allows {@code CAPTURED -> SETTLED} and
 * {@code CAPTURED -> REVERSED} (phase 6k's compensation saga) — so the
 * REST-backed watch stopped the instant a payment was captured and would never
 * have delivered a reversal that followed it. Found on the first live
 * comparison against the gRPC arm: both delivered {@code AUTHORIZED,
 * CAPTURED}, but the REST arm's stream ended at ~4s and the gRPC arm's ran the
 * full budget — because gRPC asks the real state machine and REST asked a
 * second, wrong, copy of it.
 *
 * <h2>Why a real HTTP server rather than a mocked {@code RestClient}</h2>
 *
 * <p>{@code RestClient} is built inside the constructor from a base URL, so a
 * mock would have to replace the field via reflection or change the production
 * constructor to accept one — either compromises the class for the sake of the
 * test. A same-JVM {@link HttpServer} serves real bytes over a real socket, at
 * the cost of one loopback port, and is what actually catches a divergence
 * between what the client sends and what a JSON body needs to look like.
 */
class RestOrchestratorClientWatchTest {

    private HttpServer server;
    private RestOrchestratorClient client;

    /** Every GET pops the next state off this queue; the last one repeats. */
    private final List<String> scriptedStates = new ArrayList<>();
    private int scriptedIndex;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/v1/payments/", exchange -> {
            synchronized (this) {
                String state = scriptedStates.isEmpty() ? "AUTHORIZED"
                        : scriptedStates.get(Math.min(scriptedIndex, scriptedStates.size() - 1));
                if (scriptedIndex < scriptedStates.size() - 1) {
                    scriptedIndex++;
                }

                String body = """
                        {"id":"pay_1","merchantId":"m1","state":"%s","amountMinor":4200,
                         "currency":"INR","cardBin":"424242","cardLast4":"4242",
                         "pspId":"mockpsp","providerRef":"ref_1","errorCode":null,
                         "merchantReference":"ref","createdAt":"2026-08-21T10:00:00Z",
                         "updatedAt":"2026-08-21T10:00:00Z"}
                        """.formatted(state);

                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        server.start();

        client = new RestOrchestratorClient(
                "http://localhost:" + server.getAddress().getPort(),
                new DeadlinePropagation(30_000),
                new DeadlineExecutor(50, 30_000),
                ObservationRegistry.NOOP);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void script(String... states) {
        scriptedStates.addAll(List.of(states));
    }

    // ---------------------------------------------------------------------
    // THE REGRESSION
    // ---------------------------------------------------------------------

    /**
     * CAPTURED must not end the watch. Before the fix this test failed exactly
     * this way: only {@code [AUTHORIZED, CAPTURED]} was ever seen, because the
     * loop returned the instant it observed CAPTURED and never polled again to
     * find the REVERSED that followed.
     */
    @Test
    void aCapturedPaymentThatIsLaterReversedIsStillDelivered() {
        script("AUTHORIZED", "CAPTURED", "CAPTURED", "REVERSED");

        List<String> delivered = new ArrayList<>();
        client.watch("pay_1", Duration.ofSeconds(3), 40, p -> delivered.add(p.state()));

        assertThat(delivered)
                .as("a watch that stops at CAPTURED can never deliver the reversal that follows it")
                .containsExactly("AUTHORIZED", "CAPTURED", "REVERSED");
    }

    /**
     * The states this machine truly has no outgoing edge from. Checked
     * individually so a future change to one of them fails on the right line
     * rather than in a combined assertion nobody reads closely.
     */
    @Test
    void trueTerminalStatesDoEndTheWatch() {
        for (String terminal : List.of("FAILED", "REVERSED", "SETTLED", "UNRESOLVED")) {
            scriptedStates.clear();
            scriptedIndex = 0;
            script(terminal, "SHOULD_NOT_BE_POLLED_AGAIN");

            List<String> delivered = new ArrayList<>();
            long start = System.nanoTime();
            client.watch("pay_1", Duration.ofSeconds(5), 40, p -> delivered.add(p.state()));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(delivered).as(terminal).containsExactly(terminal);
            assertThat(elapsedMs)
                    .as("%s should end the watch immediately, not run the full 5s budget", terminal)
                    .isLessThan(2_000);
        }
    }

    /**
     * UNKNOWN is not terminal either — {@code PaymentTransitions} allows
     * {@code UNKNOWN -> AUTHORIZED} and {@code UNKNOWN -> FAILED}, which is the
     * entire point of phase 8's resolution poller. A watch that stopped at
     * UNKNOWN would never deliver the resolution.
     */
    @Test
    void unknownDoesNotEndTheWatch() {
        script("UNKNOWN", "UNKNOWN", "AUTHORIZED");

        List<String> delivered = new ArrayList<>();
        client.watch("pay_1", Duration.ofSeconds(3), 40, p -> delivered.add(p.state()));

        assertThat(delivered).containsExactly("UNKNOWN", "AUTHORIZED");
    }

    // ---------------------------------------------------------------------
    // THE BUDGET, so the tests above are about state and not about timing out
    // ---------------------------------------------------------------------

    @Test
    void anOngoingPaymentIsWatchedUntilTheBudgetRunsOut() {
        script("AUTHORIZING");

        List<String> delivered = new ArrayList<>();
        long start = System.nanoTime();
        client.watch("pay_1", Duration.ofMillis(500), 40, p -> delivered.add(p.state()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(delivered).as("no change, so one emission").containsExactly("AUTHORIZING");
        assertThat(elapsedMs).isBetween(400L, 1_500L);
    }

    /** Every poll returning the same state emits exactly once, not once per poll. */
    @Test
    void unchangedStateIsNotReDelivered() {
        script("AUTHORIZED");

        List<String> delivered = new ArrayList<>();
        client.watch("pay_1", Duration.ofMillis(500), 40, p -> delivered.add(p.state()));

        assertThat(delivered).hasSize(1);
    }
}
