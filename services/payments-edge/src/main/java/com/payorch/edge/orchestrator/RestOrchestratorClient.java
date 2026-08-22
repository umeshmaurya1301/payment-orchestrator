package com.payorch.edge.orchestrator;

import java.time.Instant;
import java.util.Optional;

import org.infra.resilience.deadline.DeadlineExecutor;
import org.infra.resilience.deadline.DeadlinePropagation;
import org.springframework.web.client.HttpClientErrorException;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls {@code payment-orchestrator} over REST.
 *
 * <p><strong>Phase 3a: bounded by the deadline budget.</strong> This is the hop
 * that stamps it - 30 s, and the edge deliberately ignores any budget a merchant
 * sends, because trusting that header would let one header hold a connection for
 * as long as the caller liked.
 *
 * <p>The request record is a deliberate second copy of the orchestrator's own,
 * rather than a shared DTO module. Two services sharing a jar share a release
 * cycle; the duplication is a dozen lines and it is what keeps them
 * independently deployable. Phase 9's protobuf definitions become the check that
 * the two copies still agree.
 */
public class RestOrchestratorClient implements OrchestratorClient {

    private final RestClient client;
    private final DeadlineExecutor deadlines;
    private final ObservationRegistry observations;

    public RestOrchestratorClient(String baseUrl, DeadlinePropagation propagation,
                                  DeadlineExecutor deadlines, ObservationRegistry observations) {
        this.observations = observations;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(propagation)
                // Phase 4. Without this the trace stops at this service.
                //
                // Boot instruments RestClient through the container's
                // RestClient.Builder, and every client in this system is built
                // by hand with RestClient.builder() - the same reason 3a's
                // DeadlinePropagation is contributed as a bean rather than
                // through a RestClientCustomizer. A customizer would apply to
                // nothing at all, quietly, while looking like it covered
                // everything.
                //
                // The registry is what injects `traceparent` on the way out and
                // opens the client span. A missing one does not fail: it
                // produces a trace that ends at the caller and a downstream
                // service whose spans have a different trace id, which reads as
                // "the trace is broken" rather than "instrumentation is absent".
                .observationRegistry(observations)
                .build();
        this.deadlines = deadlines;
    }

    @Override
    public PaymentResponse create(CreatePaymentRequest request) {
        return deadlines.callWithin("orchestrator.create", () -> {
            try {
                PaymentResponse response = client.post()
                        .uri("/internal/v1/payments")
                        .body(request)
                        .retrieve()
                        .body(PaymentResponse.class);
                if (response == null) {
                    throw new OrchestratorUnavailableException(null);
                }
                return response;
            } catch (RestClientException e) {
                throw new OrchestratorUnavailableException(e);
            }
        });
    }

    /**
     * Phase 6j. Capture, proxied through.
     *
     * <p>A 409 from the orchestrator - not capturable, already captured, the
     * provider refused - is a genuine answer about the payment and is passed
     * back rather than turned into an {@code OrchestratorUnavailableException}.
     * Wrapping it would tell the merchant "we do not know what happened" about
     * a case where we know exactly what happened.
     */
    @Override
    public PaymentResponse capture(String paymentId) {
        return deadlines.callWithin("orchestrator.capture", () -> {
            try {
                PaymentResponse response = client.post()
                        .uri("/internal/v1/payments/{id}/capture", paymentId)
                        .retrieve()
                        .body(PaymentResponse.class);
                if (response == null) {
                    throw new OrchestratorUnavailableException(null);
                }
                return response;
            } catch (HttpClientErrorException e) {
                throw new CaptureRefusedException(e.getStatusCode().value(),
                        e.getResponseBodyAsString());
            } catch (RestClientException e) {
                throw new OrchestratorUnavailableException(e);
            }
        });
    }

    /**
     * Watching, over a transport that cannot. Phase 9a item 4, the control arm.
     *
     * <p>HTTP/1.1 has no server push, so this is a loop: call {@code find},
     * compare, emit on change, sleep, repeat. Every iteration is a full request
     * — connection from the pool, deadline scope, JSON parse, database read on
     * the far side — and all but the last returns exactly what the previous one
     * did.
     *
     * <p><strong>It is kept, and it is the point of keeping it.</strong> The
     * gRPC arm's claim is that a stream costs less than this. That claim is only
     * checkable if this exists and runs, which is the same reason phase 6 kept
     * the dual-write publisher runnable and phase 9a keeps both transports
     * wired: an arm that has been deleted cannot be re-measured, only quoted.
     *
     * <p>The interval is a parameter, held equal to the gRPC arm's server-side
     * interval by whatever is measuring them. Different intervals would measure
     * the interval.
     */
    @Override
    public void watch(String paymentId, java.time.Duration budget, long intervalMs,
                      java.util.function.Consumer<PaymentResponse> onUpdate) {
        long deadline = System.nanoTime() + budget.toNanos();
        String lastState = null;

        while (System.nanoTime() < deadline) {
            Optional<PaymentResponse> found;
            try {
                // findInternal, not find: find() opens its own deadline scope
                // per call, and a watch is ONE logical operation with one budget
                // - nesting a fresh 30s scope inside it every iteration would
                // let a watch outlive the request that started it.
                found = findInternal(paymentId);
            } catch (OrchestratorUnavailableException e) {
                // A blip mid-watch is not a reason to end the watch. The budget
                // is what ends it, and giving up on the first failed poll would
                // make this arm look worse than it is for a reason unrelated to
                // polling.
                found = Optional.empty();
            }

            if (found.isPresent()) {
                PaymentResponse payment = found.get();
                if (!payment.state().equals(lastState)) {
                    onUpdate.accept(payment);
                    lastState = payment.state();
                }
                if (TERMINAL.contains(payment.state())) {
                    return;
                }
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The edge's copy of the state machine's endings, and it is a copy.
     *
     * <p>The gRPC arm asks {@code PaymentState.isTerminal()} — the state machine
     * itself — because it runs inside the orchestrator. This arm cannot: the edge
     * does not have the enum, deliberately, because sharing it would make two
     * services share a release cycle for the sake of a list of strings.
     *
     * <p>So this is a second source of truth about which states are final, and
     * experiment 28 caught the failure it produces on the first live run rather
     * than the one this javadoc originally predicted. The predicted failure was
     * a watch that never ends — a state added to the machine and forgotten here,
     * holding the connection open for its whole budget. <strong>The failure that
     * actually happened was the opposite and worse: a state removed from
     * "final" too early.</strong>
     *
     * <p>{@code CAPTURED} was listed here as terminal. It is not —
     * {@code PaymentTransitions} allows {@code CAPTURED -> SETTLED} and
     * {@code CAPTURED -> REVERSED}, the latter being phase 6k's compensation
     * saga. With {@code CAPTURED} in this set, the REST-backed watch completed
     * the instant a payment was captured and would never have delivered a
     * reversal that followed — the stream would already be closed, and closed
     * quietly, with no error to notice. Compare the gRPC arm, which asks the
     * real state machine and kept running past {@code CAPTURED} exactly as it
     * should.
     *
     * <p>So this set names only the states with <strong>no outgoing edges at
     * all</strong> in {@code PaymentTransitions.ALLOWED} — verified against that
     * table, not against what a payment "usually" does after capture.
     */
    private static final java.util.Set<String> TERMINAL =
            java.util.Set.of("FAILED", "REVERSED", "SETTLED", "UNRESOLVED");

    @Override
    public Optional<PaymentResponse> find(String paymentId) {
        return deadlines.callWithin("orchestrator.find", () -> findInternal(paymentId));
    }

    private Optional<PaymentResponse> findInternal(String paymentId) {
        try {
            return Optional.ofNullable(client.get()
                    .uri("/internal/v1/payments/{id}", paymentId)
                    .retrieve()
                    .body(PaymentResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            // A 404 is an answer, not a failure. Translating it here keeps the
            // controller from having to know about HTTP status codes at all.
            return Optional.empty();
        } catch (RestClientException e) {
            throw new OrchestratorUnavailableException(e);
        }
    }

}
