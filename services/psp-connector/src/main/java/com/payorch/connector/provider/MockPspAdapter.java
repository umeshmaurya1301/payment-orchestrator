package com.payorch.connector.provider;

import java.time.Instant;

import com.payorch.infra.tokenization.DetokenizedCard;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The adapter for {@code mock-psp-simulator}.
 *
 * <p><strong>Zero resilience.</strong> No retry, no circuit breaker, no
 * bulkhead, no timeout - not even a connect timeout. That is a phase-1
 * constraint, not an oversight.
 *
 * <p>Phase 2 runs this under load with {@code hangRate} turned up and watches
 * the thread pool saturate. If a read timeout were already configured, the
 * calls would fail fast, the pool would drain, and the single most convincing
 * graph in the project would show a mild dip instead of a collapse. Every
 * defence added here before that measurement destroys the "before" half of a
 * before/after pair.
 *
 * <p>The one thing that is here is the {@code reference} being passed straight
 * through as the provider's idempotency key. That is not resilience - it is a
 * correctness property, and adding it later would mean phase 3's first retry
 * experiment double-charged before anyone noticed.
 */
public class MockPspAdapter implements PspAdapter {

    public static final String PSP_ID = "mockpsp";

    private final RestClient client;

    public MockPspAdapter(String baseUrl) {
        // Built with defaults on purpose: whatever request factory Spring picks
        // has no read timeout and no connect timeout. Phase 3 replaces this
        // construction wholesale, after phase 2 has measured what that costs.
        this.client = RestClient.builder().baseUrl(baseUrl).build();
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

        ProviderResponse response;
        try {
            response = client.post()
                    .uri("/psp/v1/authorize")
                    .body(request)
                    .retrieve()
                    .body(ProviderResponse.class);
        } catch (RestClientException e) {
            // Covers a connection failure, a 5xx, and a body that would not
            // parse. All three mean the same thing to the caller: no answer.
            throw new ProviderUnavailableException(PSP_ID, e);
        }
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
