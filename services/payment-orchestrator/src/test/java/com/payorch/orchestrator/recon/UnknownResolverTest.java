package com.payorch.orchestrator.recon;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.payorch.infra.resilience.lock.RedisLock;
import com.payorch.orchestrator.PaymentPersistence;
import com.payorch.orchestrator.connector.ConnectorApi;
import com.payorch.orchestrator.connector.ConnectorClient;
import com.payorch.orchestrator.domain.Payment;
import com.payorch.orchestrator.domain.PaymentAttempt;
import com.payorch.orchestrator.domain.PaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8a. Deciding what happened to a payment nobody could account for.
 *
 * <p>Every test here is about a decision that moves - or deliberately does not
 * move - real money into a terminal state. The dangerous one is not "does it
 * resolve", it is <strong>"does it ever conclude FAILED on incomplete
 * evidence"</strong>: a payment failed on the strength of a slow provider is a
 * charge this system has abandoned while the customer's statement disagrees.
 */
class UnknownResolverTest {

    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();

    private PaymentPersistence persistence;
    private ConnectorClient connector;
    private RedisLock lock;
    private UnknownResolver resolver;

    private Payment payment;

    @BeforeEach
    void setUp() {
        persistence = mock(PaymentPersistence.class);
        connector = mock(ConnectorClient.class);
        lock = mock(RedisLock.class);

        payment = mock(Payment.class);
        when(payment.getId()).thenReturn(PAYMENT);
        when(payment.getMerchantId()).thenReturn(UUID.randomUUID());
        when(payment.getAmountMinor()).thenReturn(4200L);
        when(payment.getCurrency()).thenReturn("INR");
        when(payment.getResolutionAttempts()).thenReturn(0);

        PaymentAttempt attempt = mock(PaymentAttempt.class);
        when(attempt.getId()).thenReturn(ATTEMPT);
        when(persistence.attemptsFor(PAYMENT)).thenReturn(List.of(attempt));
        when(persistence.unknownDueForPolling(anyInt())).thenReturn(List.of(payment));

        // The lock always grants, except where a test says otherwise. Its own
        // behaviour is RedisLockTest's subject; here it is a gate that has to be
        // open for anything else to be observable.
        grantLock();

        resolver = new UnknownResolver(persistence, connector, lock,
                50, 3, 60_000, 3_600_000, 120_000);
    }

    @SuppressWarnings("unchecked")
    private void grantLock() {
        when(lock.runIfAcquired(any(), any(Duration.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Object> work = invocation.getArgument(2);
                    return Optional.ofNullable(work.get());
                });
    }

    private ConnectorApi.LookupResponse answer(String claimedBy, ConnectorApi.Outcome outcome,
                                               List<String> answered, List<String> silent) {
        return new ConnectorApi.LookupResponse("ref", claimedBy, outcome,
                false, false, 4200, answered, silent);
    }

    // --- the three conclusive answers -------------------------------------

    /**
     * THE OUTCOME THE STATE EXISTS FOR. The provider authorized the card and
     * lost the response; five phases of payments have sat in UNKNOWN because
     * nothing ever went back and asked.
     */
    @Test
    void aProviderThatHasItApprovedResolvesToAuthorized() {
        when(connector.lookup(any())).thenReturn(
                answer("mockpsp", ConnectorApi.Outcome.APPROVED, List.of("mockpsp", "psp-b"), List.of()));

        assertThat(resolver.poll()).isEqualTo(1);

        verify(persistence).resolveUnknownToAuthorized(PAYMENT, "mockpsp");
        assertThat(resolver.resolvedAuthorizedCount()).isEqualTo(1);
    }

    @Test
    void aProviderThatHasItDeclinedResolvesToFailed() {
        when(connector.lookup(any())).thenReturn(
                answer("mockpsp", ConnectorApi.Outcome.DECLINED, List.of("mockpsp", "psp-b"), List.of()));

        assertThat(resolver.poll()).isEqualTo(1);

        verify(persistence).resolveUnknownToFailed(PAYMENT);
        assertThat(resolver.resolvedFailedCount()).isEqualTo(1);
    }

    /**
     * Every provider was asked, every one answered, none has it. The request
     * never landed anywhere, so no money moved and FAILED is the truth rather
     * than a guess.
     */
    @Test
    void everyProviderAnsweringNoResolvesToFailed() {
        when(connector.lookup(any())).thenReturn(
                answer(null, null, List.of("mockpsp", "psp-b", "psp-c"), List.of()));

        assertThat(resolver.poll()).isEqualTo(1);

        verify(persistence).resolveUnknownToFailed(PAYMENT);
    }

    // --- the answer that is not an answer ----------------------------------

    /**
     * THE ONE THAT MATTERS MOST.
     *
     * <p>Two providers said no and one said nothing. That is <em>not</em> "nobody
     * has it" - the silent one may be holding an authorized payment - and failing
     * on it would abandon a charge the customer can see on their statement.
     *
     * <p>The payment stays UNKNOWN and is asked again later. This is the
     * assertion that would fail first if somebody simplified
     * {@code definitivelyAbsent()} into a null check.
     */
    @Test
    void aSilentProviderIsNeverTreatedAsANo() {
        when(connector.lookup(any())).thenReturn(
                answer(null, null, List.of("mockpsp", "psp-b"), List.of("psp-c")));

        assertThat(resolver.poll())
                .as("nothing was resolved - the evidence is incomplete")
                .isZero();

        verify(persistence, never()).resolveUnknownToFailed(any());
        verify(persistence, never()).resolveUnknownToAuthorized(any(), any());
        verify(persistence).recordPollAttempt(eq(PAYMENT), any(Instant.class));
        assertThat(resolver.inconclusiveCount()).isEqualTo(1);
    }

    /**
     * A lookup that never reached the connector teaches nothing either. Counted
     * separately from a silent provider because the two say different things:
     * this one is about us, that one is about a provider.
     */
    @Test
    void aConnectorOutageCostsAnAttemptAndResolvesNothing() {
        when(connector.lookup(any()))
                .thenThrow(new ConnectorClient.ConnectorUnavailableException(null));

        assertThat(resolver.poll()).isZero();

        verify(persistence, never()).resolveUnknownToFailed(any());
        verify(persistence).recordPollAttempt(eq(PAYMENT), any(Instant.class));
        assertThat(resolver.lookupFailureCount()).isEqualTo(1);
        assertThat(resolver.inconclusiveCount())
                .as("an outage here is not a provider staying silent")
                .isZero();
    }

    // --- giving up ---------------------------------------------------------

    /**
     * The bound phase 8's trap list asks for: <em>"payments stuck in UNKNOWN
     * forever, polled forever, is a slow-motion outage"</em>.
     */
    @Test
    void thePollerGivesUpOnceItHasAskedAsOftenAsItMay() {
        when(payment.getResolutionAttempts()).thenReturn(2);
        when(connector.lookup(any())).thenReturn(
                answer(null, null, List.of("mockpsp"), List.of("psp-c")));

        assertThat(resolver.poll()).isEqualTo(1);

        verify(persistence).giveUpOnUnknown(PAYMENT);
        verify(persistence, never()).recordPollAttempt(any(), any());
        assertThat(resolver.gaveUpCount()).isEqualTo(1);
    }

    /** One attempt short of the bound, it schedules another rather than giving up. */
    @Test
    void oneAttemptShortOfTheBoundItTriesAgain() {
        when(payment.getResolutionAttempts()).thenReturn(1);
        when(connector.lookup(any())).thenReturn(
                answer(null, null, List.of("mockpsp"), List.of("psp-c")));

        assertThat(resolver.poll()).isZero();

        verify(persistence).recordPollAttempt(eq(PAYMENT), any(Instant.class));
        verify(persistence, never()).giveUpOnUnknown(any());
    }

    /**
     * A payment with no attempt has nothing to ask about - nothing was ever sent
     * under any name - so there is no point polling it eight times first.
     */
    @Test
    void aPaymentWithNoAttemptIsGivenUpOnImmediately() {
        when(persistence.attemptsFor(PAYMENT)).thenReturn(List.of());

        assertThat(resolver.poll()).isEqualTo(1);

        verify(connector, never()).lookup(any());
        verify(persistence).giveUpOnUnknown(PAYMENT);
    }

    // --- backoff -----------------------------------------------------------

    /**
     * The backoff grows, and it is capped.
     *
     * <p>The cap is what makes the attempt bound meaningful in time. Uncapped
     * doubling from a minute reaches four days by the eighth attempt, so "eight
     * attempts" would mean "a week" and the payment would be functionally
     * abandoned long before it was formally given up on.
     */
    @Test
    void theBackoffGrowsBetweenAttemptsAndIsCapped() {
        UnknownResolver capped = new UnknownResolver(persistence, connector, lock,
                50, 20, 60_000, 300_000, 120_000);
        when(connector.lookup(any())).thenReturn(
                answer(null, null, List.of("mockpsp"), List.of("psp-c")));

        ArgumentCaptor<Instant> scheduled = ArgumentCaptor.forClass(Instant.class);

        when(payment.getResolutionAttempts()).thenReturn(0);
        capped.poll();
        when(payment.getResolutionAttempts()).thenReturn(2);
        capped.poll();
        when(payment.getResolutionAttempts()).thenReturn(15);
        capped.poll();

        verify(persistence, org.mockito.Mockito.times(3))
                .recordPollAttempt(eq(PAYMENT), scheduled.capture());
        List<Instant> times = scheduled.getAllValues();
        Instant now = Instant.now();

        long first = Duration.between(now, times.get(0)).toSeconds();
        long third = Duration.between(now, times.get(1)).toSeconds();
        long far = Duration.between(now, times.get(2)).toSeconds();

        assertThat(first).as("first retry, one minute").isBetween(50L, 70L);
        assertThat(third).as("third retry, four minutes").isBetween(230L, 250L);
        assertThat(far)
                .as("capped at five minutes rather than doubling to weeks")
                .isBetween(290L, 310L);
    }

    // --- the lock ----------------------------------------------------------

    /**
     * The lock is a gate, not a suggestion: an instance that does not hold it
     * does no work at all.
     */
    @Test
    void anInstanceThatDoesNotHoldTheLockDoesNothing() {
        when(lock.runIfAcquired(any(), any(Duration.class), any(Supplier.class)))
                .thenReturn(Optional.empty());

        assertThat(resolver.poll()).isZero();

        verify(persistence, never()).unknownDueForPolling(anyInt());
        verify(connector, never()).lookup(any());
    }

    // --- batch behaviour ---------------------------------------------------

    /**
     * One unresolvable payment must not abandon the rest of the batch.
     *
     * <p>Without this, a single payment that throws would block every payment
     * behind it in the ordering - forever, since the ordering is by wait time
     * and it would stay at the front. That is the starvation the ordered query
     * was meant to prevent, reintroduced by an unguarded loop.
     */
    @Test
    void onePaymentThrowingDoesNotAbandonTheBatch() {
        Payment poisoned = mock(Payment.class);
        UUID poisonedId = UUID.randomUUID();
        when(poisoned.getId()).thenReturn(poisonedId);
        when(persistence.attemptsFor(poisonedId))
                .thenThrow(new IllegalStateException("database hiccup"));
        when(persistence.unknownDueForPolling(anyInt())).thenReturn(List.of(poisoned, payment));

        when(connector.lookup(any())).thenReturn(
                answer("mockpsp", ConnectorApi.Outcome.APPROVED, List.of("mockpsp"), List.of()));

        assertThat(resolver.poll())
                .as("the healthy payment behind it must still be resolved")
                .isEqualTo(1);
        verify(persistence).resolveUnknownToAuthorized(PAYMENT, "mockpsp");
    }

    @Test
    void anEmptyBacklogIsNotWork() {
        when(persistence.unknownDueForPolling(anyInt())).thenReturn(List.of());

        assertThat(resolver.poll()).isZero();

        verify(connector, never()).lookup(any());
        assertThat(resolver.polledCount()).isZero();
    }

    /** The lookup asks about the attempt id, which is the reference we sent. */
    @Test
    void theLookupAsksAboutTheReferenceWeActuallySent() {
        when(connector.lookup(any())).thenReturn(
                answer(null, null, List.of("mockpsp"), List.of()));

        resolver.poll();

        ArgumentCaptor<ConnectorApi.LookupRequest> asked =
                ArgumentCaptor.forClass(ConnectorApi.LookupRequest.class);
        verify(connector).lookup(asked.capture());
        assertThat(asked.getValue().reference()).isEqualTo(ATTEMPT.toString());
    }

    /** Nothing here may ever re-authorize. That is the double charge. */
    @Test
    void theResolverNeverReAuthorizes() {
        when(connector.lookup(any())).thenReturn(
                answer(null, null, List.of("mockpsp"), List.of("psp-c")));

        resolver.poll();

        // The connector's only mutating methods. A resolver that called either
        // would be retrying an authorization whose outcome is unknown, which is
        // the exact failure PaymentState.UNKNOWN was created to prevent.
        verify(connector, never()).authorize(any());
        verify(connector, never()).capture(any());
        verify(connector, never()).reverse(any());
    }

    /** A resolved payment leaves the polled population entirely. */
    @Test
    void aResolvedPaymentIsNotRescheduled() {
        when(connector.lookup(any())).thenReturn(
                answer("mockpsp", ConnectorApi.Outcome.APPROVED, List.of("mockpsp"), List.of()));

        resolver.poll();

        verify(persistence, never()).recordPollAttempt(any(), any());
    }

    /** State is moved through the persistence layer, never assigned directly. */
    @Test
    void theResolverDoesNotTouchStateItself() {
        when(connector.lookup(any())).thenReturn(
                answer("mockpsp", ConnectorApi.Outcome.APPROVED, List.of("mockpsp"), List.of()));

        resolver.poll();

        verify(payment, never()).transitionTo(any(PaymentState.class));
    }
}
