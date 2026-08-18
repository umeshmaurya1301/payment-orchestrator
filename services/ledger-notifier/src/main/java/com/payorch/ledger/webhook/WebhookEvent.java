package com.payorch.ledger.webhook;

import java.time.Instant;
import java.util.UUID;

import com.payorch.ledger.consume.PaymentEventMessage;

/**
 * What a merchant is told, and nothing else.
 *
 * <h2>Not the Kafka message</h2>
 *
 * <p>It would be one line to serialize {@link PaymentEventMessage} straight out
 * to the merchant, and it would be a mistake for two reasons that pull in the
 * same direction.
 *
 * <p><strong>It is a published contract.</strong> An internal event is ours to
 * change; a webhook body is an integration a dozen merchants have written code
 * against. Making them the same type means every internal field rename is a
 * breaking change to somebody else's parser, and the "just add a field" that
 * {@code @JsonIgnoreProperties} makes safe INSIDE our system is not safe once it
 * leaves it.
 *
 * <p><strong>It is a wider audience.</strong> The Kafka message carries
 * {@code cardToken}, which is a reference into our vault. It is worthless to an
 * attacker without the vault, and it is also of no use whatsoever to the
 * merchant - so it does not go. The rule this project has applied at every
 * boundary is that a field travels because somebody needs it, not because it was
 * already in scope.
 *
 * <p>What remains is the BIN and the last four, which phase 1 already stores in
 * plain text and which the merchant needs to show a customer which card was
 * used.
 *
 * @param id        unique per webhook, and the receiver's idempotency key.
 *                  Delivery is at-least-once - see {@link WebhookDispatcher} -
 *                  so this is the field that lets a merchant tell a redelivery
 *                  from a second payment.
 * @param createdAt when the payment reached this state, not when the webhook was
 *                  sent. Those differ by the relay lag plus any retry tiers, and
 *                  a merchant reconciling by time needs the former.
 */
public record WebhookEvent(
        UUID id,
        String type,
        Instant createdAt,
        Data data) {

    public record Data(
            UUID paymentId,
            UUID merchantId,
            String state,
            long amountMinor,
            String currency,
            String pspId,
            String cardBin,
            String cardLast4) {
    }

    public static WebhookEvent from(PaymentEventMessage event) {
        return new WebhookEvent(
                event.eventId(),
                event.type(),
                event.occurredAt(),
                new Data(
                        event.paymentId(),
                        event.merchantId(),
                        event.state(),
                        event.amountMinor(),
                        event.currency(),
                        event.pspId(),
                        event.cardBin(),
                        event.cardLast4()));
    }
}
