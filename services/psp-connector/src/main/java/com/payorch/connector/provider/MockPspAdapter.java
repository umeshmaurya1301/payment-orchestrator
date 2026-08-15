package com.payorch.connector.provider;

import java.time.Instant;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.retry.Retrier;
import com.payorch.infra.tokenization.DetokenizedCard;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The adapter for {@code mock-psp-simulator}.
 *
 * <p><strong>Phase 3a and 3b.</strong> The call is bounded by whatever is left
 * of the request's budget, and retried - subject to classification, an
 * idempotency reference, a budget and the deadline. Still no circuit breaker;
 * that is 3c, after its own measurement.
 *
 * <p><strong>Retry outside, deadline inside.</strong> Each attempt gets its own
 * slice of the remaining budget rather than all attempts sharing one bound, so
 * a retry cannot overrun the request and the slice naturally shrinks as time is
 * spent. The retrier consults the deadline again before backing off, so a
 * backoff that would not leave room for another call is never slept through.
 *
 * <p>The {@code reference} passed to the retrier is the same value carried in
 * the request body - the orchestrator's attempt id, which the provider treats
 * as an idempotency key. That is what makes retrying a possibly-processed
 * authorization safe rather than a double charge, and phase 1 built it for
 * exactly this moment.
 */
public class MockPspAdapter implements PspAdapter {

    public static final String PSP_ID = "mockpsp";

    private final RestClient client;
    private final DeadlineExecutor deadlines;
    private final Retrier retrier;

    public MockPspAdapter(String baseUrl,
                          DeadlinePropagation propagation,
                          DeadlineExecutor deadlines,
                          Retrier retrier) {
        // The request factory still has no read timeout, and that is now
        // deliberate rather than an omission: a factory-level timeout is shared
        // by every call, and a budget is per request by definition. The bound
        // comes from DeadlineExecutor instead, which can also actually abort.
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(propagation)
                .build();
        this.deadlines = deadlines;
        this.retrier = retrier;
    }

    @Override
    public String pspId() {
        return PSP_ID;
    }

    @Override
    public ProviderAuthorization authorize(AuthorizeCommand command, DetokenizedCard card) {
        ProviderRequest request = new ProviderRequest(
                command.reference(),
                command.amountMinor(),
                command.currency(),
                card.pan(),
                card.expiryMonth(),
                card.expiryYear());

        // command.reference() is the orchestrator's attempt id, and it is in the
        // request body above. Passing the SAME value as the idempotency
        // reference is what permits a retry of a call that may already have been
        // processed - the provider recognises the repeat and returns the
        // original authorization instead of creating a second.
        ProviderResponse response = retrier.call("mockpsp.authorize", command.reference(), () ->
                deadlines.callWithin("mockpsp.authorize", () -> {
                    try {
                        return client.post()
                                .uri("/psp/v1/authorize")
                                .body(request)
                                .retrieve()
                                .body(ProviderResponse.class);
                    } catch (RestClientException e) {
                        // Deliberately thrown with the RestClientException as its
                        // cause: FailureClassifier walks the cause chain, and a
                        // 5xx and a refused connection must classify differently
                        // even though both arrive as "no answer" here.
                        throw new ProviderUnavailableException(PSP_ID, e);
                    }
                }));
        if (response == null) {
            throw new ProviderUnavailableException(PSP_ID, null);
        }

        boolean approved = "APPROVED".equals(response.outcome());
        return new ProviderAuthorization(
                response.providerRef(), approved, response.errorCode(), response.authCode());
    }

    /**
     * The outbound authorization body.
     *
     * <p><strong>{@code pan} is deliberately not annotated with
     * {@code @Sensitive}, and must not be.</strong> That annotation rewrites the
     * property's serializer on every mapper the logging starter is registered
     * on - including the one that renders this request body. Marking the field
     * would send the provider {@code 424242******4242}, every authorization
     * would fail for a reason no log line explains, and the annotation would
     * look like the safe choice while causing an outage.
     *
     * <p>This record is protected differently: it exists for the duration of one
     * call, is never logged, never stored and never returned, and
     * {@code MockPspAdapterTest} asserts that its serialized form still carries
     * the real number. That test is there to fail loudly if someone adds the
     * annotation as a "fix".
     */
    record ProviderRequest(
            String reference,
            long amountMinor,
            String currency,
            String pan,
            int expiryMonth,
            int expiryYear) {

        /** Records print every component by default; this one must not. */
        @Override
        public String toString() {
            return "ProviderRequest[reference=" + reference + ", amountMinor=" + amountMinor
                    + ", currency=" + currency + ", pan=****]";
        }
    }

    record ProviderResponse(
            String providerRef,
            String reference,
            String outcome,
            String errorCode,
            String authCode,
            long amountMinor,
            String currency,
            String last4,
            Instant createdAt) {
    }
}
