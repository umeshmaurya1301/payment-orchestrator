package com.payorch.orchestrator.connector;

import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.payorch.infra.resilience.deadline.Deadlines;
import com.payorch.proto.v1.AuthorizeRequest;
import com.payorch.proto.v1.AuthorizeResponse;
import com.payorch.proto.v1.CaptureRequest;
import com.payorch.proto.v1.CaptureResponse;
import com.payorch.proto.v1.ConnectorGrpc;
import com.payorch.proto.v1.LookupRequest;
import com.payorch.proto.v1.LookupResponse;
import com.payorch.proto.v1.Outcome;
import com.payorch.proto.v1.ReverseRequest;
import com.payorch.proto.v1.ReverseResponse;

/**
 * The connector over gRPC. Phase 9a.
 *
 * <h2>The deadline is the reason this exists</h2>
 *
 * <p>Phase 3a built deadline propagation by hand: a budget computed per hop,
 * written into an HTTP header, re-read and clamped on the other side. Its own
 * javadoc says the hand-rolled version exists so that this one can be compared
 * against it — gRPC has first-class deadlines that travel in metadata and are
 * enforced by the runtime at both ends.
 *
 * <p>So {@link #withDeadline} is the whole integration: read the remaining
 * budget from the same {@code ScopedValue} the REST client reads, and hand it to
 * the stub. gRPC does the rest, including cancelling the server's work — which
 * the header scheme cannot do, because an HTTP server that has read a deadline
 * header still has to decide to honour it.
 *
 * <p><strong>No deadline means no call.</strong> Not an unbounded one: a request
 * that has exhausted its budget must not start a provider call it cannot wait
 * for, and phase 3a's whole argument is that an unbounded call is the failure
 * this layer removes.
 *
 * <h2>Reconstructing "definitely not sent" from a gRPC status</h2>
 *
 * <p>HTTP gave this for free: the connector answers 503 when nothing was sent
 * and 502 when the outcome is unknown, and those are different numbers. gRPC has
 * no code that means "I tried and do not know" — {@code UNAVAILABLE} covers both
 * — so the server tags the unknown case in the status description and this
 * client reads the tag. See {@code ConnectorGrpcService.OUTCOME_UNKNOWN}.
 *
 * <p>Getting that mapping wrong is not a cosmetic bug. Treating a refused call
 * as unknown manufactures {@code UNKNOWN} payments the breaker exists to
 * prevent; treating an unknown call as refused marks a payment {@code FAILED}
 * while the card may have been charged. The default — an unmapped
 * {@code StatusRuntimeException} — would produce the second one.
 */
public class GrpcConnectorClient implements ConnectorClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcConnectorClient.class);

    /** Set by the server on the one status that means "the provider may have acted". */
    private static final String OUTCOME_UNKNOWN = "provider_unavailable";

    private final ConnectorGrpc.ConnectorBlockingStub stub;

    public GrpcConnectorClient(ManagedChannel channel) {
        this.stub = ConnectorGrpc.newBlockingStub(channel);
    }

    /**
     * The stub for this call, carrying whatever budget is left.
     *
     * <p>A fresh stub per call rather than a field: stubs are immutable and
     * {@code withDeadlineAfter} returns a new one, so caching a stub would cache
     * a deadline — and every subsequent call would inherit the first request's
     * budget, expiring sooner and sooner until they all failed instantly.
     */
    private ConnectorGrpc.ConnectorBlockingStub withDeadline(String operation) {
        long remaining = Deadlines.current()
                .map(com.payorch.infra.resilience.deadline.Deadline::remainingMs)
                .orElse(-1L);

        if (remaining < 0) {
            // Outside a request scope - a scheduled job, a test. The REST client
            // reaches DeadlineExecutor's fallback here; the equivalent is a
            // bounded stub rather than an unbounded one.
            return stub.withDeadlineAfter(Deadlines.currentOrDefault(5_000).remainingMs(),
                    TimeUnit.MILLISECONDS);
        }
        if (remaining == 0) {
            throw new ConnectorRejectedException(
                    new IllegalStateException("no budget left for " + operation));
        }
        return stub.withDeadlineAfter(remaining, TimeUnit.MILLISECONDS);
    }

    @Override
    public ConnectorApi.AuthorizeResponse authorize(ConnectorApi.AuthorizeRequest request) {
        try {
            AuthorizeResponse response = withDeadline("authorize").authorize(
                    AuthorizeRequest.newBuilder()
                            .setReference(request.reference())
                            .setPspId(request.pspId())
                            .setAmountMinor(request.amountMinor())
                            .setCurrency(request.currency())
                            .setCardToken(request.cardToken())
                            .setCardBin(request.cardBin())
                            .setCardLast4(request.cardLast4())
                            .build());

            return new ConnectorApi.AuthorizeResponse(
                    emptyToNull(response.getProviderRef()),
                    fromProto(response.getOutcome()),
                    emptyToNull(response.getErrorCode()),
                    emptyToNull(response.getAuthCode()));

        } catch (StatusRuntimeException e) {
            throw translate(e, "authorize");
        }
    }

    @Override
    public ConnectorApi.CaptureResponse capture(ConnectorApi.CaptureRequest request) {
        try {
            CaptureResponse response = withDeadline("capture").capture(
                    CaptureRequest.newBuilder()
                            .setProviderRef(request.providerRef())
                            .setPspId(request.pspId())
                            .setAmountMinor(request.amountMinor())
                            .build());

            return new ConnectorApi.CaptureResponse(
                    emptyToNull(response.getProviderRef()),
                    fromProto(response.getOutcome()),
                    emptyToNull(response.getErrorCode()),
                    response.getCapturedAmountMinor());

        } catch (StatusRuntimeException e) {
            throw translate(e, "capture");
        }
    }

    @Override
    public ConnectorApi.ReverseResponse reverse(ConnectorApi.ReverseRequest request) {
        try {
            ReverseResponse response = withDeadline("reverse").reverse(
                    ReverseRequest.newBuilder()
                            .setProviderRef(request.providerRef())
                            .setPspId(request.pspId())
                            .build());

            return new ConnectorApi.ReverseResponse(
                    emptyToNull(response.getProviderRef()),
                    fromProto(response.getOutcome()),
                    emptyToNull(response.getErrorCode()),
                    response.getReversedAmountMinor());

        } catch (StatusRuntimeException e) {
            throw translate(e, "reverse");
        }
    }

    @Override
    public ConnectorApi.LookupResponse lookup(ConnectorApi.LookupRequest request) {
        try {
            LookupResponse response = withDeadline("lookup").lookup(
                    LookupRequest.newBuilder().setReference(request.reference()).build());

            return new ConnectorApi.LookupResponse(
                    response.getReference(),
                    emptyToNull(response.getClaimedBy()),
                    response.getOutcome() == Outcome.OUTCOME_UNSPECIFIED
                            ? null : fromProto(response.getOutcome()),
                    response.getCaptured(),
                    response.getReversed(),
                    response.getAmountMinor(),
                    response.getAnsweredList(),
                    response.getSilentList());

        } catch (StatusRuntimeException e) {
            throw translate(e, "lookup");
        }
    }

    /**
     * The mapping, in one place.
     *
     * <p>Every branch decides one thing: did the request reach a provider? The
     * orchestrator's state machine turns that answer into {@code FAILED} or
     * {@code UNKNOWN}, and there is no third option, so a status this method does
     * not recognise has to fall to the safe side — which is {@code UNKNOWN}. A
     * payment wrongly marked unknown costs a poll; a payment wrongly marked
     * failed while the card was charged costs a refund and a customer.
     */
    private RuntimeException translate(StatusRuntimeException e, String operation) {
        Status.Code code = e.getStatus().getCode();
        String description = e.getStatus().getDescription();

        // Nothing was sent. The connector's own gates refused it locally.
        if (code == Status.Code.RESOURCE_EXHAUSTED
                || (code == Status.Code.UNAVAILABLE && !OUTCOME_UNKNOWN.equals(description))) {
            log.debug("connector refused {} locally: {} {}", operation, code, description);
            // The description is the connector's own gate name - circuit_open,
            // bulkhead_full, provider_rate_limited - so it is the accurate
            // answer to "which gate", and null when nothing tagged it.
            return new ConnectorRejectedException(
                    description == null ? "connection refused" : description, e);
        }

        // DEADLINE_EXCEEDED is UNKNOWN, and it is the one people get wrong.
        // We stopped waiting; the connector did not necessarily stop working,
        // and the provider may already have been called. gRPC will cancel the
        // server, but cancellation is not retroactive - a card charged before
        // the cancel arrived stays charged.
        if (code == Status.Code.DEADLINE_EXCEEDED) {
            log.warn("deadline exceeded on {} - the provider may have acted", operation);
            return new ConnectorUnavailableException(e);
        }

        return new ConnectorUnavailableException(e);
    }

    /**
     * Protobuf has no null. An absent string field deserializes to "", and
     * passing that up would turn "this decline had no auth code" into "the auth
     * code is the empty string" - which reads as a value everywhere downstream.
     */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static ConnectorApi.Outcome fromProto(Outcome outcome) {
        return outcome == Outcome.OUTCOME_APPROVED
                ? ConnectorApi.Outcome.APPROVED
                : ConnectorApi.Outcome.DECLINED;
    }
}
