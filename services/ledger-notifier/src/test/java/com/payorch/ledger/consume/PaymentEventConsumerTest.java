package com.payorch.ledger.consume;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.payorch.infra.chaos.ChaosSeam;
import com.payorch.infra.chaos.ChaosSeams;
import com.payorch.ledger.domain.LedgerPosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private final PaymentEventConsumer consumer = new PaymentEventConsumer(ledger, seams);

    private static PaymentEventMessage event() {
        return new PaymentEventMessage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "payment.authorized", "AUTHORIZED", 4200, "INR",
                "mockpsp", "tok_test", "424242", "4242", Instant.now());
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
                "com.payorch.infra.chaos.ChaosSeams$ChaosInjectedException",
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
}
