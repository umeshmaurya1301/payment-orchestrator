package com.payorch.ledger.webhook;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import org.infra.logging.LogEvent;
import org.infra.logging.LogFields;
import com.payorch.ledger.consume.PaymentEventMessage;

import tools.jackson.databind.ObjectMapper;

/**
 * Sends the webhook, signs it, and decides what a failure means.
 *
 * <h2>Retries are the retry ladder, and that is not laziness</h2>
 *
 * <p>There is no backoff scheduler here, no delivery-attempt table and no second
 * queue. A delivery that fails for a reason worth retrying throws, the record
 * goes back onto phase 6f's ladder - 5s, 1m, 10m, DLQ - and the consumer runs
 * again. One retry mechanism for the service, with one place to look during an
 * incident and one DLQ to replay from.
 *
 * <p>What makes that safe is the thing phase 6e built: {@code LedgerPosting} is
 * idempotent on {@code eventId}, so a redelivery re-posts nothing. What it costs
 * is that the webhook IS re-sent, because there is no record of it having gone
 * out. That is at-least-once delivery, it is what every webhook provider offers,
 * and it is why every webhook carries {@code X-Payorch-Event-Id}: the receiver
 * deduplicates, because only the receiver knows what it already acted on.
 *
 * <h2>Which failures are worth retrying, which are not</h2>
 *
 * <p>A timeout, a refused connection or a 5xx says the receiver is having a bad
 * minute. Those throw, and the ladder waits.
 *
 * <p>A 4xx says the receiver looked at the request and refused it - a bad
 * signature, a body it cannot parse, an endpoint that no longer exists. Retrying
 * that four times over eleven minutes changes nothing except the receiver's
 * error rate, so it is counted and logged loudly and NOT retried. That is a
 * deliberate drop, and the honest name for it is a drop: the event is in the
 * ledger and the merchant was not told. It is visible as
 * {@code payorch.webhook.refused} rather than as a gap.
 *
 * <p>429 is the exception inside the exception. It is a 4xx that means "later",
 * so it is retried.
 *
 * <h2>Where the trace comes from</h2>
 *
 * <p>Nothing in this class touches trace context. It is called from inside the
 * Kafka listener's observation scope, so the {@code RestClient} - built with the
 * observation registry, see {@link WebhookConfiguration} - injects
 * {@code traceparent} and opens a client span in the trace that phase 6g carried
 * across the broker. The webhook a merchant receives forty seconds after the
 * payment is one span of the API call that caused it.
 */
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final RestClient http;
    private final String url;

    /** Null when configured to send unsigned - the "before" arm of experiment 13. */
    private final WebhookSigner signer;

    private final ObjectMapper mapper;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public WebhookDispatcher(RestClient http, String url, WebhookSigner signer,
                             ObjectMapper mapper) {
        this.http = http;
        this.url = url;
        this.signer = signer;
        this.mapper = mapper;
    }

    /**
     * @throws WebhookDeliveryException when the failure is worth another attempt,
     *         which puts the record back on the retry ladder
     */
    public void dispatch(PaymentEventMessage event) {
        WebhookEvent webhook = WebhookEvent.from(event);

        // Serialized ONCE, and this String is what gets both signed and sent.
        // Serializing twice - once to sign, once to send - is the classic way to
        // produce a signature over bytes that differ from the bytes on the wire
        // by a key order nobody can see.
        String body = mapper.writeValueAsString(webhook);

        RestClient.RequestBodySpec request = http.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSigner.EVENT_ID_HEADER, String.valueOf(webhook.id()));

        if (signer != null) {
            request = request.header(WebhookSigner.SIGNATURE_HEADER,
                    signer.sign(Instant.now(), body));
        }

        try {
            request.body(body).retrieve().toBodilessEntity();
            sent.incrementAndGet();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                failed.incrementAndGet();
                throw new WebhookDeliveryException("receiver asked us to slow down", e);
            }
            refused.incrementAndGet();
            log.warn("webhook refused by the receiver and NOT retried - the merchant "
                            + "was not told about this payment",
                    LogEvent.event()
                            .with(LogFields.PAYMENT_ID, String.valueOf(event.paymentId()))
                            .with(LogFields.STATE, event.state())
                            .with(LogFields.HTTP_STATUS, e.getStatusCode().value())
                            .with(LogFields.OUTCOME, "WEBHOOK_REFUSED")
                            .args());
        } catch (RestClientException e) {
            failed.incrementAndGet();
            throw new WebhookDeliveryException("webhook delivery failed", e);
        }
    }

    /** Delivered and acknowledged with a 2xx. */
    public long sent() {
        return sent.get();
    }

    /** The receiver looked at it and said no. Dropped on purpose - see the javadoc. */
    public long refused() {
        return refused.get();
    }

    /** Could not be delivered. Retried by the ladder. */
    public long failed() {
        return failed.get();
    }

    /** Thrown to put the record back on phase 6f's ladder. */
    public static class WebhookDeliveryException extends RuntimeException {
        public WebhookDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
