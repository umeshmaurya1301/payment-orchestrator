package com.payorch.connector.provider;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import com.payorch.connector.config.ProviderConfig;
import com.payorch.connector.config.ProviderConfigStore;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.infra.resilience.breaker.CircuitBreakers;
import com.payorch.infra.resilience.bulkhead.Bulkhead;
import com.payorch.infra.resilience.ratelimit.RateLimitedException;
import com.payorch.infra.resilience.ratelimit.RateLimiter;
import com.payorch.infra.resilience.retry.Retrier;
import com.payorch.infra.observability.ProviderLatency;
import com.payorch.infra.observability.ProviderOutcomes;
import com.payorch.infra.observability.Seams;
import io.micrometer.observation.ObservationRegistry;
import com.payorch.infra.tokenization.DetokenizedCard;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The adapter for anything speaking the {@code mock-psp-simulator} protocol.
 *
 * <p>One instance per provider from phase 3f. The class used to be tied to a
 * single provider with a base URL from YAML; it now takes a {@code pspId} and
 * reads everything else - address, timeouts, retry ceiling, breaker thresholds,
 * bulkhead width, contracted TPS - from {@code psp_config} through
 * {@link ProviderConfigStore}, which refreshes it while the service runs.
 *
 * <p><strong>Retry, then breaker, then bulkhead, then egress limit, then
 * deadline.</strong> The nesting is a real decision:
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
 *   <li>The bulkhead sits <em>inside</em> the breaker so an open breaker costs
 *       no permit - it never intended to make a call, and consuming capacity to
 *       decline would throttle the provider's recovery.</li>
 *   <li>The egress limiter sits inside the bulkhead so a token is spent if and
 *       only if a request actually goes out. Outside it, every call the bulkhead
 *       later refused would still have consumed a token, throttling us below the
 *       contract we are trying to fill.</li>
 *   <li>The deadline is innermost, so each attempt gets its own slice of the
 *       remaining budget, and a permit is held for exactly the duration of the
 *       call rather than across the backoff between attempts.</li>
 * </ul>
 *
 * <p>The {@code reference} passed to the retrier is the same value carried in
 * the request body - the orchestrator's attempt id, which the provider treats
 * as an idempotency key. That is what makes retrying a possibly-processed
 * authorization safe rather than a double charge, and phase 1 built it for
 * exactly this moment.
 */
public class MockPspAdapter implements PspAdapter {

    /** The phase-1 provider. Kept as a constant because config and tests name it. */
    public static final String PSP_ID = "mockpsp";

    private final String pspId;
    private final ProviderConfigStore configs;
    private final DeadlinePropagation propagation;
    private final DeadlineExecutor deadlines;
    private final Retrier retrier;
    private final CircuitBreakers breakers;
    private final Bulkhead bulkhead;
    private final RateLimiter egressLimiter;
    private final ObservationRegistry observations;
    private final ProviderLatency providerLatency;
    private final ProviderOutcomes providerOutcomes;
    private final Seams seams;

    /**
     * Rebuilt only when the configured address changes.
     *
     * <p>Not per call: a {@code RestClient} carries the message converters, and
     * rebuilding it per request would re-create the whole Jackson stack on the
     * hot path. Not once at construction either, because {@code base_url} is now
     * a database column that an operator can repoint at a standby endpoint
     * without a deploy - which is most of the point of 3f.
     */
    private final AtomicReference<AddressedClient> client = new AtomicReference<>();

    private record AddressedClient(String baseUrl, RestClient client) {
    }

    public MockPspAdapter(String pspId,
                          ProviderConfigStore configs,
                          DeadlinePropagation propagation,
                          DeadlineExecutor deadlines,
                          Retrier retrier,
                          CircuitBreakers breakers,
                          Bulkhead bulkhead,
                          RateLimiter egressLimiter,
                          ObservationRegistry observations,
                          ProviderLatency providerLatency,
                          ProviderOutcomes providerOutcomes,
                          Seams seams) {
        this.pspId = pspId;
        this.configs = configs;
        this.propagation = propagation;
        this.deadlines = deadlines;
        this.retrier = retrier;
        this.breakers = breakers;
        this.bulkhead = bulkhead;
        this.egressLimiter = egressLimiter;
        this.observations = observations;
        this.providerLatency = providerLatency;
        this.providerOutcomes = providerOutcomes;
        this.seams = seams;
    }

    private ProviderConfig config() {
        return configs.find(pspId).orElseThrow(() -> new IllegalStateException(
                "no psp_config row for '" + pspId + "'"));
    }

    /**
     * The client for the currently configured address.
     *
     * <p>The request factory still has no read timeout, and that is deliberate
     * rather than an omission: a factory-level timeout is shared by every call,
     * and a budget is per request by definition. The bound comes from
     * {@link DeadlineExecutor} instead, which can also actually abort.
     */
    private RestClient clientFor(ProviderConfig config) {
        AddressedClient existing = client.get();
        if (existing != null && existing.baseUrl().equals(config.baseUrl())) {
            return existing.client();
        }
        AddressedClient rebuilt = new AddressedClient(config.baseUrl(),
                RestClient.builder()
                        .baseUrl(config.baseUrl())
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
                        .build());
        client.set(rebuilt);
        return rebuilt.client();
    }

    @Override
    public String pspId() {
        return pspId;
    }

    /**
     * 3e's egress layer: spend a token from <em>the provider's</em> contracted
     * TPS, or decline without sending.
     *
     * <p>Innermost of the four gates, and that placement is the decision. A token
     * here is spent if and only if a request is about to go out - put the check
     * outside the bulkhead and every call the bulkhead later refuses would still
     * have consumed a token, throttling us below the contract we are trying to
     * fill. The permit is held across one Redis round trip, which is the price of
     * charging accurately.
     *
     * <p>Retries are charged too, and must be: the provider's rate limit counts
     * requests it receives, and it has no interest in which of ours were second
     * attempts.
     */
    private <T> T egress(Callable<T> call) throws Exception {
        RateLimiter.Decision decision = egressLimiter.tryAcquire(pspId);
        if (!decision.allowed()) {
            throw new RateLimitedException(pspId, decision.retryAfterMs());
        }
        return call.call();
    }


    /**
     * One provider attempt: spanned, and timed into the window phase 5 routes on.
     *
     * <p>A named method rather than another lambda in the chain. The gates above
     * are already five deep, and a sixth would put the only line that actually
     * talks to a provider behind six closing parentheses - which is how a
     * misplaced bracket becomes a resilience bug nobody can see.
     *
     * <p>Both wrappers sit <strong>innermost</strong>, around the call itself, so
     * they measure what the provider cost rather than what our own queueing did.
     * Timing the bulkhead instead would fold permit-wait into the provider's
     * latency, and phase 5 would route away from a fast provider because
     * <em>we</em> were saturated - the same attribution error
     * {@code ProviderFault} exists to prevent for the circuit breaker.
     *
     * <p>Failures are timed too. A provider taking four seconds to return a 500
     * is slow, and a percentile computed only over successes reports it as
     * healthy right up until it stops answering at all.
     */
    private ProviderResponse measured(RestClient client, ProviderRequest request) throws Exception {
        long startedAt = System.nanoTime();
        boolean answered = false;
        try {
            ProviderResponse response = seams.inSpan(Seams.PROVIDER_CALL,
                    () -> send(client, request), "psp", pspId, "operation", "authorize");
            answered = true;
            return response;
        } finally {
            providerLatency.record(pspId, "authorize",
                    (System.nanoTime() - startedAt) / 1_000_000);
            // Phase 5's fourth signal. `answered`, not `approved` - a DECLINE is
            // the provider working correctly, and scoring it as ill health would
            // route traffic away from whichever provider is best at refusing
            // stolen cards. Only a throw means the provider itself failed.
            providerOutcomes.record(pspId, "authorize", answered);
        }
    }

    private ProviderResponse send(RestClient client, ProviderRequest request) {
        try {
            return client.post()
                    .uri("/psp/v1/authorize")
                    .body(request)
                    .retrieve()
                    .body(ProviderResponse.class);
        } catch (RestClientException e) {
            // Thrown with the RestClientException as its cause: both
            // FailureClassifier and ProviderFault walk the cause chain, and a
            // 5xx, a refused connection and a 429 must be told apart even though
            // all three arrive as "no answer" here.
            throw new ProviderUnavailableException(pspId, e);
        }
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

        // Read once per authorization, not once per attempt. A config change
        // landing between two retries of the same payment would mean the two
        // attempts obeyed different rules, and the resulting numbers would
        // describe neither configuration.
        ProviderConfig config = config();
        RestClient client = clientFor(config);
        String operation = pspId + ".authorize";

        // command.reference() is the orchestrator's attempt id, and it is in the
        // request body above. Passing the SAME value as the idempotency
        // reference is what permits a retry of a call that may already have been
        // processed - the provider recognises the repeat and returns the
        // original authorization instead of creating a second.
        ProviderResponse response = retrier.call(operation, command.reference(),
                config.retryMaxAttempts(),
                () -> breakers.call(pspId, "authorize",
                () -> bulkhead.call(pspId,
                () -> egress(
                () -> deadlines.callWithin(operation, config.deadlineSliceMs(),
                () -> measured(client, request))))));
        if (response == null) {
            throw new ProviderUnavailableException(pspId, null);
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
