package com.payorch.edge.orchestrator;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.infra.resilience.deadline.Deadlines;
import com.payorch.proto.v1.CapturePaymentRequest;
import com.payorch.proto.v1.GetPaymentRequest;
import com.payorch.proto.v1.PaymentsGrpc;

/**
 * The edge's orchestrator hop, over gRPC. Phase 9a.
 *
 * <h2>The deadline is stamped here, and only here</h2>
 *
 * <p>This is the hop that establishes the budget for the whole request — 30
 * seconds, and the edge deliberately ignores any budget a merchant sends,
 * because trusting that header would let one header hold a connection for as
 * long as the caller liked. {@code trust-inbound-header: false} in the edge's
 * configuration is that decision.
 *
 * <p>Phase 9a learned what happens when the budget is cut anywhere else: setting
 * {@code DEADLINE_BUDGET_MS=2000} on the <em>orchestrator</em> changed nothing,
 * because the orchestrator trusts its inbound header and the edge's 30 seconds
 * won. <strong>A budget has to be cut where it originates.</strong> So this
 * client reads the same {@code ScopedValue} the REST arm reads and hands the
 * remainder to gRPC, which enforces it at both ends — the part the header scheme
 * cannot do, because an HTTP server that has read a deadline header still has to
 * decide to honour it.
 *
 * <h2>The status mapping has a third outcome the connector's did not</h2>
 *
 * <p>ADR 0009 mapped connector statuses onto one question — was the request
 * sent, or not — because the orchestrator's state machine has exactly two
 * branches. This hop has three: unavailable, refused, and <em>the payment does
 * not exist</em>. That last one is not a failure and must not be wrapped as one;
 * {@code NOT_FOUND} becomes {@code Optional.empty()}, precisely as the REST arm
 * turns a 404 into one.
 */
public class GrpcOrchestratorClient implements OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcOrchestratorClient.class);

    private final PaymentsGrpc.PaymentsBlockingStub stub;
    private final long defaultBudgetMs;

    public GrpcOrchestratorClient(ManagedChannel channel, long defaultBudgetMs) {
        this.stub = PaymentsGrpc.newBlockingStub(channel);
        this.defaultBudgetMs = defaultBudgetMs;
    }

    /**
     * A fresh stub per call, carrying whatever budget is left.
     *
     * <p>Stubs are immutable and {@code withDeadlineAfter} returns a new one, so
     * caching a stub caches a <em>deadline</em> — and every later call inherits
     * the first request's budget, expiring sooner and sooner until they all fail
     * instantly. The channel underneath is the thing that is shared, and it is a
     * singleton bean.
     */
    private PaymentsGrpc.PaymentsBlockingStub withDeadline() {
        long remaining = Deadlines.current()
                .map(org.infra.resilience.deadline.Deadline::remainingMs)
                .orElse(-1L);

        if (remaining <= 0) {
            // No scope, or none left. Bounded rather than unbounded either way:
            // phase 3a's whole argument is that an unbounded call is the failure
            // this layer removes, and Deadlines.currentOrDefault exists for the
            // paths that are reached without a scope.
            return stub.withDeadlineAfter(
                    Deadlines.currentOrDefault(defaultBudgetMs).remainingMs(), TimeUnit.MILLISECONDS);
        }
        return stub.withDeadlineAfter(remaining, TimeUnit.MILLISECONDS);
    }

    @Override
    public PaymentResponse create(CreatePaymentRequest request) {
        try {
            com.payorch.proto.v1.CreatePaymentRequest.Builder b =
                    com.payorch.proto.v1.CreatePaymentRequest.newBuilder()
                            .setMerchantId(request.merchantId())
                            .setAmountMinor(request.amountMinor())
                            .setCurrency(request.currency())
                            .setCardToken(request.cardToken())
                            .setCardBin(request.cardBin())
                            .setCardLast4(request.cardLast4());
            if (request.merchantReference() != null) {
                b.setMerchantReference(request.merchantReference());
            }
            return fromProto(withDeadline().create(b.build()));

        } catch (StatusRuntimeException e) {
            throw translate(e, "create");
        }
    }

    @Override
    public PaymentResponse capture(String paymentId) {
        try {
            return fromProto(withDeadline().capture(
                    CapturePaymentRequest.newBuilder().setPaymentId(paymentId).build()));

        } catch (StatusRuntimeException e) {
            throw translate(e, "capture");
        }
    }

    @Override
    public Optional<PaymentResponse> find(String paymentId) {
        try {
            return Optional.of(fromProto(withDeadline().get(
                    GetPaymentRequest.newBuilder().setPaymentId(paymentId).build())));

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                // An answer, not a failure.
                return Optional.empty();
            }
            throw translate(e, "find");
        }
    }

    /**
     * The server stream. One call, and the orchestrator does the watching.
     *
     * <p>A blocking iterator rather than an async observer: this runs on the
     * thread serving the merchant's SSE connection, which is a virtual thread,
     * so blocking it costs a continuation rather than a platform thread. The
     * async form would need its own executor and a way to propagate failures
     * back out, to arrive at the same place.
     *
     * <p>{@code CANCELLED} and {@code DEADLINE_EXCEEDED} end the stream quietly.
     * Both mean "we stopped", which is the normal ending for a watch that ran
     * out of budget or a merchant who closed the page — turning either into an
     * error would fill the log with routine departures.
     */
    @Override
    public void watch(String paymentId, java.time.Duration budget, long intervalMs,
                      java.util.function.Consumer<PaymentResponse> onUpdate) {
        try {
            var stream = stub
                    .withDeadlineAfter(budget.toMillis(), TimeUnit.MILLISECONDS)
                    .watch(com.payorch.proto.v1.WatchPaymentRequest.newBuilder()
                            .setPaymentId(paymentId)
                            .setPollIntervalMs((int) intervalMs)
                            .build());

            while (stream.hasNext()) {
                onUpdate.accept(fromProto(stream.next()));
            }

        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.CANCELLED || code == Status.Code.DEADLINE_EXCEEDED) {
                log.debug("watch of {} ended: {}", paymentId, code);
                return;
            }
            if (code == Status.Code.NOT_FOUND) {
                // Nothing to watch. The endpoint has already answered 404 for
                // this case, so reaching here means the payment vanished
                // between two calls, which cannot happen - log it rather than
                // swallow it.
                log.warn("watch of {} found no payment", paymentId);
                return;
            }
            throw translate(e, "watch");
        }
    }

    /**
     * One place where a status becomes an exception the edge understands.
     *
     * <p>{@code FAILED_PRECONDITION} and {@code INVALID_ARGUMENT} are the
     * orchestrator answering; everything else is the absence of an answer. The
     * default is deliberately the second, because a merchant told "we do not
     * know" about a payment that was actually refused can retry safely, while a
     * merchant told "refused" about a payment we could not reach may have been
     * charged.
     */
    private RuntimeException translate(StatusRuntimeException e, String operation) {
        Status.Code code = e.getStatus().getCode();

        if (code == Status.Code.FAILED_PRECONDITION) {
            // Phase 6j's refusal - not capturable, already captured, provider
            // said no - which the edge renders as 409, the same status the REST
            // arm passes through.
            return new CaptureRefusedException(409, describe(e));
        }
        if (code == Status.Code.INVALID_ARGUMENT) {
            return new CaptureRefusedException(400, describe(e));
        }
        if (code == Status.Code.DEADLINE_EXCEEDED) {
            log.warn("deadline exceeded on orchestrator.{} - the payment may still be running",
                    operation);
        }
        return new OrchestratorUnavailableException(e);
    }

    private static String describe(StatusRuntimeException e) {
        return e.getStatus().getDescription() == null ? "refused" : e.getStatus().getDescription();
    }

    private static PaymentResponse fromProto(com.payorch.proto.v1.PaymentResponse r) {
        return new PaymentResponse(
                emptyToNull(r.getId()),
                emptyToNull(r.getMerchantId()),
                emptyToNull(r.getState()),
                r.getAmountMinor(),
                emptyToNull(r.getCurrency()),
                emptyToNull(r.getCardBin()),
                emptyToNull(r.getCardLast4()),
                emptyToNull(r.getPspId()),
                emptyToNull(r.getProviderRef()),
                emptyToNull(r.getErrorCode()),
                emptyToNull(r.getMerchantReference()),
                instant(r.getCreatedAtMs()),
                instant(r.getUpdatedAtMs()));
    }

    /**
     * Protobuf has no null: an unset string arrives as {@code ""}. Passing that
     * up turns "this payment has no error code" into "the error code is the
     * empty string", which reads as a value in every log line and JSON field
     * downstream — and the edge echoes several of these straight to a merchant.
     */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Likewise for times: 0 means absent, not 1970. */
    private static Instant instant(long millis) {
        return millis == 0L ? null : Instant.ofEpochMilli(millis);
    }
}
