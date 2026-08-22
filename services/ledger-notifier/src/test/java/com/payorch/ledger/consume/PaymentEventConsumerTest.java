package com.payorch.ledger.consume;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.infra.chaos.ChaosSeam;
import org.infra.chaos.ChaosSeams;
import org.springframework.beans.factory.ObjectProvider;

import com.payorch.ledger.domain.LedgerPosting;
import com.payorch.ledger.saga.CompensationPublisher;
import com.payorch.ledger.saga.CompensationRequest;
import com.payorch.ledger.webhook.WebhookDispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for phase 6f, and the first two are the ones that matter.
 *
 * <p>The dead-letter handler threw on its first real record - it passed field
 * names that {@code LogEvent} rejects against the {@code LogFields} allowlist -
 * and because {@code @RetryableTopic} defaults to
 * {@code DltStrategy.ALWAYS_RETRY_ON_ERROR}, the failing record was republished
 * to the DLQ it was already on. Four messages became 10,306 records in three
 * minutes.
 *
 * <p>The production fix is two-part: {@code FAIL_ON_ERROR} so the loop cannot
 * form, and a catch around the handler body so it cannot throw at all. That
 * second half is what makes a test necessary rather than optional - a
 * catch-everything around the last line of defence will happily hide the exact
 * bug it exists to survive, and a run with a broken DLQ log line would look
 * identical to a healthy one. Hence the counter, and hence this assertion on it.
 */
class PaymentEventConsumerTest {

    private final LedgerPosting ledger = mock(LedgerPosting.class);
    private final ChaosSeams seams = new ChaosSeams();
    private final WebhookDispatcher webhooks = mock(WebhookDispatcher.class);
    private final CompensationPublisher compensations = mock(CompensationPublisher.class);
    private final PaymentEventConsumer consumer =
            new PaymentEventConsumer(ledger, seams, providerOf(webhooks), compensations);

    /**
     * The dispatcher is an ObjectProvider in production because a deployment
     * with webhooks off has no such bean. Mockito cannot mock a generic
     * interface usefully here, so this is the two-line real thing.
     */
    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }
        };
    }

    private static PaymentEventMessage event() {
        return event("payment.authorized", "AUTHORIZED");
    }

    private static PaymentEventMessage event(String type, String state) {
        return new PaymentEventMessage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                type, state, 4200, "INR",
                "mockpsp", "tok_test", "424242", "4242", Instant.now());
    }

    private static PaymentEventMessage capture() {
        return event("payment.captured", "CAPTURED");
    }

    /** Four bytes, big-endian, which is how Spring writes the attempts header. */
    private static byte[] attempts(int n) {
        return ByteBuffer.allocate(4).putInt(n).array();
    }

    /**
     * The regression. Every field this handler logs must be on the allowlist,
     * and the counter is the only thing that can say so now that the body is
     * wrapped.
     */
    @Test
    void theDeadLetterHandlerLogsWithoutTrippingTheLogFieldAllowlist() {
        consumer.onDeadLetter(event(),
                "payment.events.retry-600000".getBytes(StandardCharsets.UTF_8),
                "org.infra.chaos.ChaosSeams$ChaosInjectedException",
                attempts(4));

        assertThat(consumer.deadLettered()).isEqualTo(1);
        assertThat(consumer.dltLogFailures())
                .as("the DLT handler's own logging must not fail - see the class javadoc")
                .isZero();
    }

    /**
     * A record produced onto the DLQ by hand, or by an older version of the
     * producer, has none of Spring's headers. The end of the ladder is exactly
     * where a missing header must not become a second failure.
     */
    @Test
    void theDeadLetterHandlerSurvivesMissingHeaders() {
        assertThatNoException().isThrownBy(() ->
                consumer.onDeadLetter(event(), null, null, null));

        assertThat(consumer.deadLettered()).isEqualTo(1);
        assertThat(consumer.dltLogFailures()).isZero();
    }

    /**
     * The seam fires BEFORE the ledger write, so a failed delivery leaves no
     * trace to clean up. If it fired after, this phase would be re-testing phase
     * 6e's unique constraint instead of the retry ladder.
     */
    @Test
    void anArmedSeamFailsTheListenerBeforeAnythingIsPosted() {
        seams.arm(PaymentEventConsumer.SEAM, ChaosSeam.fail());

        assertThatThrownBy(() -> consumer.onPaymentEvent(event(), RetryTopics.MAIN))
                .isInstanceOf(ChaosSeams.ChaosInjectedException.class);

        verify(ledger, never()).post(any());
        assertThat(consumer.consumed()).isZero();
    }

    /**
     * The retried counter is what distinguishes "no failures happened" from
     * "failures happened and were silently dropped". It must count deliveries
     * from a tier and must not count deliveries from the main topic.
     */
    @Test
    void deliveriesFromARetryTierAreCountedSeparately() {
        when(ledger.post(any())).thenReturn(true);

        consumer.onPaymentEvent(event(), RetryTopics.MAIN);
        assertThat(consumer.retried()).isZero();

        consumer.onPaymentEvent(event(), RetryTopics.RETRY_5S);
        consumer.onPaymentEvent(event(), RetryTopics.RETRY_10M);
        assertThat(consumer.retried()).isEqualTo(2);
        assertThat(consumer.consumed()).isEqualTo(3);
    }

    /**
     * A redelivery the unique constraint recognised is at-least-once working,
     * not an error, and it must be counted apart from real work.
     */
    @Test
    void aDuplicateIsCountedButNotTreatedAsAFailure() {
        when(ledger.post(any())).thenReturn(false);

        assertThatNoException().isThrownBy(() ->
                consumer.onPaymentEvent(event(), RetryTopics.MAIN));

        assertThat(consumer.duplicates()).isEqualTo(1);
        assertThat(consumer.consumed()).isEqualTo(1);
    }

    /**
     * Phase 6h. The webhook goes out for a duplicate too, and that is not a bug.
     *
     * <p>{@code applied == false} means "the ledger already had this event", not
     * "the merchant already heard about it". Skipping the dispatch there makes a
     * single webhook failure permanent: the ladder redelivers, the post dedupes,
     * and the webhook is never attempted again. Re-sending is at-least-once,
     * which is the contract, and the event id is how the receiver tells.
     */
    @Test
    void aDuplicateStillDispatchesItsWebhook() {
        when(ledger.post(any())).thenReturn(false);

        consumer.onPaymentEvent(event(), RetryTopics.MAIN);

        verify(webhooks).dispatch(any());
        assertThat(consumer.duplicates()).isEqualTo(1);
    }

    /**
     * Order matters more than it looks: a merchant must never be told about
     * money the ledger does not hold. If the post throws, nothing is sent.
     */
    @Test
    void nothingIsDispatchedWhenTheLedgerWriteFails() {
        when(ledger.post(any())).thenThrow(new IllegalStateException("constraint"));

        assertThatThrownBy(() -> consumer.onPaymentEvent(event(), RetryTopics.MAIN))
                .isInstanceOf(IllegalStateException.class);

        verify(webhooks, never()).dispatch(any());
    }

    /**
     * A delivery failure has to reach the container, because that is what puts
     * the record back on the ladder. A try/catch around the dispatch would turn
     * "the merchant was not told" into a silent outcome.
     */
    @Test
    void aWebhookFailurePropagatesSoTheLadderCanRetry() {
        when(ledger.post(any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new WebhookDispatcher.WebhookDeliveryException(
                        "receiver down", new RuntimeException()))
                .when(webhooks).dispatch(any());

        assertThatThrownBy(() -> consumer.onPaymentEvent(event(), RetryTopics.MAIN))
                .isInstanceOf(WebhookDispatcher.WebhookDeliveryException.class);
    }

    /**
     * The topic header is optional on the listener signature, so a container
     * that does not supply it must not become a null-pointer at the front of the
     * ledger's write path.
     */
    @Test
    void aMissingTopicHeaderIsNotAFailure() {
        when(ledger.post(any())).thenReturn(true);

        assertThatNoException().isThrownBy(() -> consumer.onPaymentEvent(event(), null));
        assertThat(consumer.consumed()).isEqualTo(1);
        assertThat(consumer.retried()).isZero();
    }

    // --- phase 6k: what the end of the ladder now does -------------------

    /**
     * A dead-lettered capture that never reached the ledger is money at a
     * provider that this service cannot account for. That, and only that, is
     * what justifies asking for a reversal.
     */
    @Test
    void aDeadLetteredCaptureWithNoLedgerEntryRequestsACompensation() {
        when(ledger.hasEntryFor(any())).thenReturn(false);

        consumer.onDeadLetter(capture(), null, null, null);

        verify(compensations).request(any(), eq(CompensationRequest.LEDGER_DEAD_LETTERED));
        assertThat(consumer.compensationsSkipped()).isZero();
    }

    /**
     * THE GUARD THAT STOPS THE COMPENSATION BECOMING THE INCIDENT.
     *
     * <p>A capture can reach the DLQ with its ledger entries already posted: the
     * write succeeds and the WEBHOOK then fails four times because a merchant's
     * endpoint is down. The books are complete and the merchant simply has not
     * been told. Reversing on that basis would take back money the ledger says
     * is owed, to fix somebody else's HTTP 502.
     */
    @Test
    void aDeadLetteredCaptureThatWasAlreadyPostedIsNotCompensated() {
        when(ledger.hasEntryFor(any())).thenReturn(true);

        consumer.onDeadLetter(capture(), null, null, null);

        verify(compensations, never()).request(any(), any());
        assertThat(consumer.compensationsSkipped()).isEqualTo(1);
    }

    /**
     * An authorization that dead-letters needs no compensation - an
     * authorization nobody captured expires at the provider by itself, and
     * reversing one would be undoing something nobody has collected.
     */
    @Test
    void aDeadLetteredAuthorizationIsNeverCompensated() {
        consumer.onDeadLetter(event(), null, null, null);

        verify(compensations, never()).request(any(), any());
        verify(ledger, never()).hasEntryFor(any());
    }

    /**
     * Still the rule that governs this method: nothing thrown from the DLT
     * handler, ever. A database that is down when the guard runs must not turn
     * the end of the ladder into a failure - phase 6f measured what happens
     * when it does.
     */
    @Test
    void aFailureDecidingWhetherToCompensateDoesNotEscapeTheDltHandler() {
        when(ledger.hasEntryFor(any())).thenThrow(new IllegalStateException("db down"));

        assertThatNoException().isThrownBy(() ->
                consumer.onDeadLetter(capture(), null, null, null));

        assertThat(consumer.deadLettered()).isEqualTo(1);
        assertThat(consumer.dltLogFailures()).isZero();
    }
}
