package com.payorch.connector.provider;

import java.time.Instant;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.breaker.CircuitBreakers;
import com.payorch.infra.resilience.retry.Retrier;
import com.payorch.infra.tokenization.DetokenizedCard;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The adapter for {@code mock-psp-simulator}.
 *
 * <p><strong>Phase 3a, 3b and 3c.</strong> The call is bounded by the remaining
 * budget, retried subject to classification and a budget, and gated by a circuit
 * breaker for this provider and operation.
 *
 * <p><strong>Retry outside, breaker in the middle, deadline innermost.</strong>
 * The nesting is a real decision:
 *
 * <ul>
 *   <li>The breaker sits <em>inside</em> the retry so it counts every network
 *       attempt. With the retry inside, three retried failures would reach the
 *       breaker as one, and it would take three times the sustained failure to
 *       open.</li>
 *   <li>Once the breaker is open, the retry does not fight it:
 *       {@code CallNotPermittedException} is unrecognised by
 *       {@code FailureClassifier}, which defaults to not retrying. The two
 *       components compose into fast failure without either knowing about the
 *       other.</li>
 *   <li>The deadline is innermost, so each attempt gets its own slice of the
 *       remaining budget.</li>
 * </ul>
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
    private final CircuitBreakers breakers;

    public MockPspAdapter(String baseUrl,
                          DeadlinePropagation propagation,
                          DeadlineExecutor deadlines,
                          Retrier retrier,
                          CircuitBreakers breakers) {
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
        this.breakers = breakers;
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
                breakers.call(PSP_ID, "authorize", () ->
                        deadlines.callWithin("mockpsp.authorize", () -> {
                            try {
                                return client.post()
                                        .uri("/psp/v1/authorize")
                                        .body(request)
                                        .retrieve()
                                        .body(ProviderResponse.class);
                            } catch (RestClientException e) {
                                // Thrown with the RestClientException as its
                                // cause: both FailureClassifier and ProviderFault
                                // walk the cause chain, and a 5xx, a refused
                                // connection and a 429 must be told apart even
                                // though all three arrive as "no answer" here.
                                throw new ProviderUnavailableException(PSP_ID, e);
                            }
                        })));
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
