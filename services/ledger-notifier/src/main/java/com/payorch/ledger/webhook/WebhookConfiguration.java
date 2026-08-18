package com.payorch.ledger.webhook;

import java.time.Duration;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

/**
 * Wires outbound webhooks. Phase 6h.
 *
 * <p>Everything here is behind {@code payorch.webhooks.enabled}, off by default,
 * for the same reason {@code payorch.events.publisher} defaults to {@code none}:
 * a service that posted to a merchant's URL the moment it started would make
 * every earlier experiment depend on a receiver being up.
 */
@Configuration
@ConditionalOnProperty(name = "payorch.webhooks.enabled", havingValue = "true")
public class WebhookConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebhookConfiguration.class);

    /**
     * The signer, or no bean at all.
     *
     * <p>{@code payorch.webhooks.sign=false} sends unsigned - which is the
     * "before" arm of experiment 13, kept selectable rather than deleted so the
     * measurement can be re-run rather than quoted. An absent bean rather than a
     * disabled one, because a signer that has been told not to sign is one
     * refactor away from signing with an empty secret.
     */
    @Bean
    @ConditionalOnProperty(name = "payorch.webhooks.sign", havingValue = "true",
            matchIfMissing = true)
    public WebhookSigner webhookSigner(@Value("${payorch.webhooks.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            // Loud, and fatal. The alternative - fall back to unsigned - means a
            // typo'd environment variable silently downgrades every webhook this
            // service will ever send, and nothing downstream can tell the
            // difference between "not configured to sign" and "configured to
            // sign and failed to".
            throw new IllegalStateException(
                    "payorch.webhooks.sign is on and payorch.webhooks.secret is empty. "
                            + "Set the secret, or set payorch.webhooks.sign=false to send "
                            + "unsigned deliberately.");
        }
        log.info("outbound webhooks will be signed with HMAC-SHA256");
        return new WebhookSigner(secret);
    }

    /**
     * The client. Two things on it are load-bearing and neither is a default.
     *
     * <p><strong>The observation registry.</strong> Boot instruments the
     * {@code RestClient.Builder} it hands out, and this one is built by hand, so
     * without this line the webhook call has no client span and no
     * {@code traceparent} header - the trace phase 6g carried across Kafka would
     * end at the consumer, one hop short of the criterion it exists for.
     *
     * <p><strong>The timeouts.</strong> A merchant's endpoint is somebody else's
     * infrastructure and it is entitled to hang. Without a read timeout the
     * consumer thread blocks on it indefinitely, and because ordering is per
     * partition, one unresponsive merchant would stall every payment that hashed
     * to the same partition - the head-of-line blocking phase 6f built the
     * non-blocking ladder to avoid, reintroduced one layer further out.
     */
    @Bean
    public RestClient webhookRestClient(
            ObservationRegistry observations,
            @Value("${payorch.webhooks.connect-timeout-ms:2000}") long connectMs,
            @Value("${payorch.webhooks.read-timeout-ms:5000}") long readMs) {

        // The JDK's own HttpClient, configured directly rather than through
        // Boot's ClientHttpRequestFactoryBuilder. Boot 4 moved that builder's
        // package, and this class needs two timeouts rather than a detection
        // strategy - going through spring-web's own factory keeps the wiring
        // independent of where Boot files its HTTP client helpers this release.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(connectMs))
                        .build());
        factory.setReadTimeout(Duration.ofMillis(readMs));

        return RestClient.builder()
                .requestFactory(factory)
                .observationRegistry(observations)
                .build();
    }

    @Bean
    public WebhookDispatcher webhookDispatcher(
            RestClient webhookRestClient,
            @Value("${payorch.webhooks.url}") String url,
            org.springframework.beans.factory.ObjectProvider<WebhookSigner> signer,
            ObjectMapper mapper) {

        WebhookSigner resolved = signer.getIfAvailable();
        log.warn("outbound webhooks ENABLED to {} ({})", url,
                resolved == null ? "UNSIGNED - experiment arm only" : "signed");
        return new WebhookDispatcher(webhookRestClient, url, resolved, mapper);
    }

    /**
     * {@code FunctionCounter}, not {@code Gauge} - phase 4e's lesson. A
     * cumulative value published as a gauge gets summed rather than
     * differenced by the query layer, and the resulting graph is wrong in a
     * plausible-looking way.
     */
    @Bean
    public MeterBinder webhookMetrics(WebhookDispatcher dispatcher) {
        return (MeterRegistry registry) -> {
            FunctionCounter.builder("payorch.webhook.sent", dispatcher,
                            WebhookDispatcher::sent)
                    .description("webhooks the merchant acknowledged with a 2xx")
                    .register(registry);
            FunctionCounter.builder("payorch.webhook.refused", dispatcher,
                            WebhookDispatcher::refused)
                    .description("webhooks the receiver rejected with a 4xx - dropped, not retried")
                    .register(registry);
            FunctionCounter.builder("payorch.webhook.failed", dispatcher,
                            WebhookDispatcher::failed)
                    .description("webhook deliveries that failed and went back on the retry ladder")
                    .register(registry);
        };
    }
}
