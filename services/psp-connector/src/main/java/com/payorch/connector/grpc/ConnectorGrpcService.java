package com.payorch.connector.grpc;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.payorch.connector.AuthorizationService;
import com.payorch.connector.CaptureService;
import com.payorch.connector.ReversalService;
import com.payorch.connector.api.ConnectorApi;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.StatusFanout;
import org.infra.resilience.bulkhead.BulkheadFullException;
import org.infra.resilience.ratelimit.RateLimitedException;
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

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * The same four operations as {@link com.payorch.connector.ConnectorController},
 * over gRPC.
 *
 * <h2>A second door onto the same rooms</h2>
 *
 * <p>Every method here delegates to the service the REST controller delegates
 * to, and does nothing else. That is the whole design: phase 9's benchmark
 * compares REST against gRPC under identical load, and it can only do that if
 * the two carry identical work. A gRPC path that skipped a validation or reused
 * a connection differently would produce a number about this class rather than
 * about the transport.
 *
 * <p>It also means the resilience layers are unchanged. The breaker, the
 * bulkhead, the egress limiter and the deadline slice all live inside the
 * adapters, below both controllers, so neither transport can accidentally be
 * measured with different gates.
 *
 * <h2>The status mapping is the interesting part</h2>
 *
 * <p>The REST controller's exception handlers encode a distinction phase 1 spent
 * real effort on: <strong>503 means nothing was sent and the card was definitely
 * not charged; 502 means the outcome is unknown.</strong> Collapsing them turns
 * every fast local rejection into an {@code UNKNOWN} payment needing a status
 * poll — manufacturing the uncertainty the breaker exists to avoid.
 *
 * <p>gRPC has its own status codes and the mapping has to be decided rather than
 * defaulted, or every failure becomes {@code UNKNOWN} — which in gRPC is
 * literally the name of the code you get for an unhandled exception, and which
 * means the opposite of what this system means by the word.
 *
 * <pre>
 *   breaker open / bulkhead full   UNAVAILABLE         nothing was sent
 *   egress rate limited            RESOURCE_EXHAUSTED  nothing was sent
 *   provider did not answer        UNAVAILABLE + tag   outcome unknown
 * </pre>
 *
 * <p>The last row is the one that cannot be expressed as cleanly as it is in
 * HTTP. gRPC has no code that means "I tried and do not know", so the outcome is
 * carried in the status DESCRIPTION and the mapping back to the orchestrator's
 * {@code UNKNOWN} is done there, deliberately and in one place.
 */
public class ConnectorGrpcService extends ConnectorGrpc.ConnectorImplBase {

    private static final Logger log = LoggerFactory.getLogger(ConnectorGrpcService.class);

    /**
     * The marker that distinguishes "not sent" from "sent, no answer".
     *
     * <p>A string in a status description is a poor contract and it is the one
     * gRPC offers: {@code UNAVAILABLE} is correct for both cases and gRPC has no
     * finer code. The alternative - inventing a code, or using
     * {@code DATA_LOSS} because it sounds severe - would be worse, because
     * generic retry layers act on these codes and would then act on ours.
     */
    public static final String OUTCOME_UNKNOWN = "provider_unavailable";

    private final AuthorizationService authorizations;
    private final CaptureService captures;
    private final ReversalService reversals;
    private final StatusFanout fanout;

    public ConnectorGrpcService(AuthorizationService authorizations, CaptureService captures,
                                ReversalService reversals, StatusFanout fanout) {
        this.authorizations = authorizations;
        this.captures = captures;
        this.reversals = reversals;
        this.fanout = fanout;
    }

    @Override
    public void authorize(AuthorizeRequest request, StreamObserver<AuthorizeResponse> out) {
        respond(out, () -> {
            ConnectorApi.AuthorizeResponse response = authorizations.authorize(
                    new ConnectorApi.AuthorizeRequest(
                            request.getReference(), request.getPspId(),
                            request.getAmountMinor(), request.getCurrency(),
                            request.getCardToken(), request.getCardBin(), request.getCardLast4()));

            AuthorizeResponse.Builder built = AuthorizeResponse.newBuilder()
                    .setOutcome(toProto(response.outcome()));
            // Every setter is null-guarded. Protobuf throws NullPointerException
            // on setString(null) rather than treating it as absent, and a
            // declined authorization legitimately has no authCode.
            if (response.providerRef() != null) {
                built.setProviderRef(response.providerRef());
            }
            if (response.errorCode() != null) {
                built.setErrorCode(response.errorCode());
            }
            if (response.authCode() != null) {
                built.setAuthCode(response.authCode());
            }
            return built.build();
        });
    }

    @Override
    public void capture(CaptureRequest request, StreamObserver<CaptureResponse> out) {
        respond(out, () -> {
            ConnectorApi.CaptureResponse response = captures.capture(
                    new ConnectorApi.CaptureRequest(
                            request.getProviderRef(), request.getPspId(), request.getAmountMinor()));

            CaptureResponse.Builder built = CaptureResponse.newBuilder()
                    .setOutcome(toProto(response.outcome()))
                    .setCapturedAmountMinor(response.capturedAmountMinor());
            if (response.providerRef() != null) {
                built.setProviderRef(response.providerRef());
            }
            if (response.errorCode() != null) {
                built.setErrorCode(response.errorCode());
            }
            return built.build();
        });
    }

    @Override
    public void reverse(ReverseRequest request, StreamObserver<ReverseResponse> out) {
        respond(out, () -> {
            ConnectorApi.ReverseResponse response = reversals.reverse(
                    new ConnectorApi.ReverseRequest(request.getProviderRef(), request.getPspId()));

            ReverseResponse.Builder built = ReverseResponse.newBuilder()
                    .setOutcome(toProto(response.outcome()))
                    .setReversedAmountMinor(response.reversedAmountMinor());
            if (response.providerRef() != null) {
                built.setProviderRef(response.providerRef());
            }
            if (response.errorCode() != null) {
                built.setErrorCode(response.errorCode());
            }
            return built.build();
        });
    }

    @Override
    public void lookup(LookupRequest request, StreamObserver<LookupResponse> out) {
        respond(out, () -> {
            StatusFanout.FanoutResult result = fanout.askEveryone(request.getReference());
            PspAdapter.ProviderLookup claimed = result.claimed().orElse(null);

            LookupResponse.Builder built = LookupResponse.newBuilder()
                    .setReference(request.getReference())
                    .setCaptured(claimed != null && claimed.captured())
                    .setReversed(claimed != null && claimed.reversed())
                    .setAmountMinor(claimed == null ? 0 : claimed.amountMinor())
                    .addAllSilent(result.silent())
                    .addAllAnswered(result.answers().stream()
                            .map(PspAdapter.ProviderLookup::pspId).toList());
            if (claimed != null) {
                built.setClaimedBy(claimed.pspId());
                // "APPROVED".equals(...), the same test the REST controller
                // makes, and the same reasoning: anything that is not an
                // explicit approval is a decline. A provider that holds the
                // reference and will not call it approved has not approved it,
                // and guessing generously here would resolve an UNKNOWN payment
                // to AUTHORIZED on the strength of a string nobody recognised.
                built.setOutcome("APPROVED".equals(claimed.outcome())
                        ? Outcome.OUTCOME_APPROVED
                        : Outcome.OUTCOME_DECLINED);
            }
            return built.build();
        });
    }

    /**
     * One place where an exception becomes a gRPC status.
     *
     * <p>Written once rather than per method because the mapping IS the contract:
     * four copies of it would drift, and the drift would show up as a payment
     * recorded {@code UNKNOWN} for a call that never left the building.
     */
    private <T> void respond(StreamObserver<T> out, java.util.concurrent.Callable<T> work) {
        // A client that has already given up. Doing the work anyway would spend a
        // provider call - and possibly charge a card - to produce a response
        // nobody will read.
        if (Context.current().isCancelled()) {
            out.onError(Status.CANCELLED
                    .withDescription("client cancelled before the provider was called")
                    .asRuntimeException());
            return;
        }

        try {
            out.onNext(work.call());
            out.onCompleted();

        } catch (CallNotPermittedException e) {
            out.onError(Status.UNAVAILABLE
                    .withDescription("circuit_open")
                    .asRuntimeException());

        } catch (BulkheadFullException e) {
            out.onError(Status.UNAVAILABLE
                    .withDescription("bulkhead_full")
                    .asRuntimeException());

        } catch (RateLimitedException e) {
            // RESOURCE_EXHAUSTED, not UNAVAILABLE. Both mean nothing was sent;
            // this one additionally means "and it is our own budget, so retrying
            // sooner will not help" - which is the distinction the Retry-After
            // header carries in the REST arm.
            out.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("provider_rate_limited")
                    .asRuntimeException());

        } catch (PspAdapter.ProviderUnavailableException e) {
            // THE UNKNOWN CASE. The provider may have acted. See OUTCOME_UNKNOWN.
            log.warn("provider unavailable over gRPC: {}", e.getMessage());
            out.onError(Status.UNAVAILABLE
                    .withDescription(OUTCOME_UNKNOWN)
                    .asRuntimeException());

        } catch (Exception e) {
            // Deliberately NOT Status.UNKNOWN, which is what an unhandled
            // exception becomes by default and which this system reads as "the
            // card may have been charged". An error we failed to classify is an
            // INTERNAL fault of ours, and saying so keeps the word UNKNOWN
            // meaning one thing.
            log.error("unhandled error in the gRPC connector", e);
            out.onError(Status.INTERNAL
                    .withDescription("internal_error")
                    .asRuntimeException());
        }
    }

    private static Outcome toProto(ConnectorApi.Outcome outcome) {
        if (outcome == null) {
            return Outcome.OUTCOME_UNSPECIFIED;
        }
        return outcome == ConnectorApi.Outcome.APPROVED
                ? Outcome.OUTCOME_APPROVED
                : Outcome.OUTCOME_DECLINED;
    }
}
