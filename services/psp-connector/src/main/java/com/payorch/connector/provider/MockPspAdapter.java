package com.payorch.connector.provider;

import java.time.Instant;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.tokenization.DetokenizedCard;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The adapter for {@code mock-psp-simulator}.
 *
 * <p><strong>Phase 3a: bounded, and nothing more.</strong> The call is now
 * limited by whatever is left of the request's budget, and a call with too
 * little left is declined rather than started. Still no retry and no breaker -
 * those are 3b and 3c, each landing only after its own measurement.
 *
 * <p>This is the last hop, so the budget it sees has already had the edge's and
 * the orchestrator's time deducted from it. Under phase 2's {@code hangRate},
 * this is the call that used to hang forever.
 *
 * <p>The one thing that is here is the {@code reference} being passed straight
 * through as the provider's idempotency key. That is not resilience - it is a
 * correctness property, and adding it later would mean phase 3's first retry
 * experiment double-charged before anyone noticed.
 */
public class MockPspAdapter implements PspAdapter {

    public static final String PSP_ID = "mockpsp";

    private final RestClient client;
    private final DeadlineExecutor deadlines;

    public MockPspAdapter(String baseUrl, DeadlinePropagation propagation, DeadlineExecutor deadlines) {
        // The request factory still has no read timeout, and that is now
        // deliberate rather than an omission: a factory-level timeout is shared
        // by every call, and a budget is per request by definition. The bound
        // comes from DeadlineExecutor instead, which can also actually abort.
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(propagation)
                .build();
        this.deadlines = deadlines;
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

        // DeadlineExceededException propagates rather than becoming
        // ProviderUnavailableException: the caller needs to know whether the
        // request was ever sent, and collapsing it here would throw that away.
        ProviderResponse response = deadlines.callWithin("mockpsp.authorize", () -> {
            try {
                return client.post()
                        .uri("/psp/v1/authorize")
                        .body(request)
                        .retrieve()
                        .body(ProviderResponse.class);
            } catch (RestClientException e) {
                // Covers a connection failure, a 5xx, and a body that would not
                // parse. All three mean the same thing to the caller: no answer.
                throw new ProviderUnavailableException(PSP_ID, e);
            }
        });
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
