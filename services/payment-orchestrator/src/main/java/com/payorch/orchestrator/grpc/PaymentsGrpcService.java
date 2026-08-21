package com.payorch.orchestrator.grpc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.payorch.infra.persistence.Uuid7;
import com.payorch.infra.web.ApiException;
import com.payorch.orchestrator.PaymentService;
import com.payorch.orchestrator.api.OrchestratorApi;
import com.payorch.proto.v1.CapturePaymentRequest;
import com.payorch.proto.v1.CreatePaymentRequest;
import com.payorch.proto.v1.GetPaymentRequest;
import com.payorch.proto.v1.PaymentResponse;
import com.payorch.proto.v1.PaymentsGrpc;

/**
 * The same three operations as {@link com.payorch.orchestrator.PaymentController},
 * over gRPC. Phase 9a, the second internal hop.
 *
 * <h2>A second door onto the same rooms</h2>
 *
 * <p>Every method delegates to the {@link PaymentService} the REST controller
 * delegates to and does nothing else — the same arrangement
 * {@code ConnectorGrpcService} uses one hop down, for the same reason: the
 * benchmark can only compare transports if the two carry identical work. A gRPC
 * path that skipped a validation would produce a number about this class.
 *
 * <p><strong>Except for validation, which is not free here.</strong> The REST
 * controller has {@code @Valid} on its request body and gets Jakarta Bean
 * Validation applied by Spring. A gRPC service method is not a Spring MVC
 * handler, so nothing applies those constraints — and protobuf's own type system
 * does not express "currency is exactly three characters" or "amount is at
 * least 1". Left alone, the gRPC arm would accept a zero-amount payment in a
 * currency called {@code ""} that the REST arm rejects with a 400.
 *
 * <p>So the constraints are applied explicitly in {@link #validate}. It is
 * duplication, and the alternative — wiring a {@code Validator} over a
 * hand-built record — is the same duplication with more machinery. What matters
 * is that the two doors admit the same requests.
 *
 * <h2>NOT_FOUND is an answer, which the connector's mapping had no equivalent of</h2>
 *
 * <p>ADR 0009 mapped connector statuses onto a two-way question: was the request
 * sent, or not? Every status had to land on one side. This service has a third
 * kind of outcome — the payment genuinely does not exist — and it is not a
 * failure of any kind. It gets {@code NOT_FOUND} and the client turns it back
 * into {@code Optional.empty()}, exactly as the REST arm turns a 404 into one.
 */
public class PaymentsGrpcService extends PaymentsGrpc.PaymentsImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentsGrpcService.class);

    private final PaymentService payments;

    public PaymentsGrpcService(PaymentService payments) {
        this.payments = payments;
    }

    @Override
    public void create(CreatePaymentRequest request, StreamObserver<PaymentResponse> out) {
        respond(out, () -> {
            validate(request);
            return toProto(payments.create(new OrchestratorApi.CreatePaymentRequest(
                    request.getMerchantId(),
                    request.getAmountMinor(),
                    request.getCurrency(),
                    request.getCardToken(),
                    request.getCardBin(),
                    request.getCardLast4(),
                    emptyToNull(request.getMerchantReference()))));
        });
    }

    @Override
    public void capture(CapturePaymentRequest request, StreamObserver<PaymentResponse> out) {
        respond(out, () -> {
            UUID id = Uuid7.parseOrNull(request.getPaymentId());
            if (id == null) {
                throw new NotFound();
            }
            return toProto(payments.capture(id));
        });
    }

    @Override
    public void get(GetPaymentRequest request, StreamObserver<PaymentResponse> out) {
        respond(out, () -> {
            UUID id = Uuid7.parseOrNull(request.getPaymentId());
            if (id == null) {
                // A malformed id is NOT_FOUND, not INVALID_ARGUMENT. There is no
                // payment at that address, and distinguishing the two tells a
                // caller which ids happen to be well-formed - the same reasoning
                // the REST controller applies to 404 against 400.
                throw new NotFound();
            }
            Optional<OrchestratorApi.PaymentResponse> found = payments.find(id);
            if (found.isEmpty()) {
                throw new NotFound();
            }
            return toProto(found.get());
        });
    }

    /**
     * The constraints {@code @Valid} applies on the REST side.
     *
     * <p>Every one of these is a rejection the other door already makes. They
     * are INVALID_ARGUMENT rather than anything softer because the caller can
     * fix them and no provider was contacted.
     */
    private void validate(CreatePaymentRequest r) {
        require(!r.getMerchantId().isBlank(), "merchantId is required");
        require(r.getAmountMinor() >= 1, "amountMinor must be at least 1");
        require(r.getCurrency().length() == 3, "currency must be 3 characters");
        require(!r.getCardToken().isBlank() && r.getCardToken().length() <= 48,
                "cardToken is required and at most 48 characters");
        require(r.getCardBin().length() == 6, "cardBin must be 6 characters");
        require(r.getCardLast4().length() == 4, "cardLast4 must be 4 characters");
        require(r.getMerchantReference().length() <= 128,
                "merchantReference must be at most 128 characters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new Invalid(message);
        }
    }

    private <T> void respond(StreamObserver<T> out, java.util.concurrent.Callable<T> work) {
        if (Context.current().isCancelled()) {
            out.onError(Status.CANCELLED
                    .withDescription("client cancelled before the work started")
                    .asRuntimeException());
            return;
        }

        try {
            out.onNext(work.call());
            out.onCompleted();

        } catch (NotFound e) {
            out.onError(Status.NOT_FOUND.withDescription("no such payment").asRuntimeException());

        } catch (ApiException e) {
            // THE BUG THIS BRANCH EXISTS FOR, found live rather than in a test.
            //
            // A second capture of an already-captured payment answers 409
            // "Capture refused" over REST and answered 502 "the payment could
            // not be confirmed, it may or may not have been created" over gRPC -
            // because ApiException fell through to the generic handler below and
            // became INTERNAL, which the edge reads as "no answer".
            //
            // That is the worst available answer. It tells a merchant we do not
            // know, about the one case where we know exactly: the payment is
            // captured, nothing further happened, and repeating the request is
            // pointless rather than dangerous. ADR 0009 is entirely about not
            // making this mistake one hop down; this is the same mistake, one
            // hop up, in code written after that ADR.
            //
            // PaymentService raises ApiException with the HTTP status it means,
            // so the mapping is a translation rather than a decision.
            out.onError(fromHttpStatus(e).withDescription(e.errorCode()).asRuntimeException());

        } catch (Invalid e) {
            out.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());

        } catch (IllegalStateException e) {
            // Phase 6j's refusal: not capturable, already captured, the provider
            // said no. FAILED_PRECONDITION rather than INTERNAL, because it is a
            // genuine answer ABOUT the payment - the edge turns it into a 409,
            // and wrapping it as a fault would tell a merchant "we do not know
            // what happened" about a case where we know exactly what happened.
            log.debug("refused: {}", e.getMessage());
            out.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage() == null ? "refused" : e.getMessage())
                    .asRuntimeException());

        } catch (Exception e) {
            // Deliberately not Status.UNKNOWN. ADR 0009's reasoning holds one hop
            // up: UNKNOWN is what an unhandled exception becomes by default and
            // this system reads that word as "the card may have been charged".
            log.error("unhandled error in the payments gRPC service", e);
            out.onError(Status.INTERNAL.withDescription("internal_error").asRuntimeException());
        }
    }

    /**
     * An {@code ApiException}'s HTTP status, as the gRPC status that means the
     * same thing to the edge.
     *
     * <p>Only the codes this service actually raises are listed, and anything
     * unrecognised becomes {@code INTERNAL} rather than being guessed at — a
     * status silently mapped to "the caller can fix this" would turn a fault
     * into a merchant-visible rejection.
     */
    private static Status fromHttpStatus(ApiException e) {
        return switch (e.status()) {
            case NOT_FOUND -> Status.NOT_FOUND;
            // A genuine answer about the payment: not capturable, already
            // captured, no provider reference, the provider declined.
            case CONFLICT, UNPROCESSABLE_ENTITY -> Status.FAILED_PRECONDITION;
            case BAD_REQUEST -> Status.INVALID_ARGUMENT;
            case TOO_MANY_REQUESTS -> Status.RESOURCE_EXHAUSTED;
            // 502/503/504 from below - the provider hop could not answer. This
            // one genuinely IS "we do not know", and it keeps that meaning.
            case BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT -> Status.UNAVAILABLE;
            default -> Status.INTERNAL;
        };
    }

    private static PaymentResponse toProto(OrchestratorApi.PaymentResponse r) {
        PaymentResponse.Builder b = PaymentResponse.newBuilder()
                .setAmountMinor(r.amountMinor())
                .setCreatedAtMs(millis(r.createdAt()))
                .setUpdatedAtMs(millis(r.updatedAt()));
        // Protobuf throws on setString(null) rather than treating it as absent,
        // and half of these are legitimately null - providerRef while UNKNOWN,
        // errorCode on success.
        set(r.id(), b::setId);
        set(r.merchantId(), b::setMerchantId);
        set(r.state(), b::setState);
        set(r.currency(), b::setCurrency);
        set(r.cardBin(), b::setCardBin);
        set(r.cardLast4(), b::setCardLast4);
        set(r.pspId(), b::setPspId);
        set(r.providerRef(), b::setProviderRef);
        set(r.errorCode(), b::setErrorCode);
        set(r.merchantReference(), b::setMerchantReference);
        return b.build();
    }

    private static void set(String value, java.util.function.Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static long millis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Internal signals, not part of any API. */
    private static class NotFound extends RuntimeException {
    }

    private static class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }
}
