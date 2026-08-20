package com.payorch.connector.provider;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.payorch.infra.resilience.deadline.Deadline;
import com.payorch.infra.resilience.deadline.Deadlines;
import com.payorch.infra.tokenization.DetokenizedCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7f. The parallel status fan-out.
 *
 * <p>Every provider here is a stub with a controllable delay, because what these
 * assert is <strong>timing and cancellation</strong> rather than protocol. A
 * fan-out that quietly ran sequentially would pass every functional assertion
 * anybody would think to write; the only thing that catches it is the clock.
 */
class StatusFanoutTest {

    private static final String REFERENCE = "attempt-ref-1";

    private final StubAdapter fast = new StubAdapter("fast", 20);
    private final StubAdapter middling = new StubAdapter("middling", 120);
    private final StubAdapter slow = new StubAdapter("slow", 250);

    private StatusFanout fanoutOf(StubAdapter... adapters) {
        Set<String> ids = java.util.Arrays.stream(adapters)
                .map(StubAdapter::pspId)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        java.util.Map<String, PspAdapter> byId = new ConcurrentHashMap<>();
        for (StubAdapter adapter : adapters) {
            byId.put(adapter.pspId(), adapter);
        }
        // 5s fallback: these run outside any request scope, so there is no
        // deadline to inherit and the fan-out needs a bound from somewhere.
        return new StatusFanout(new StubRegistry(ids, byId), 5_000);
    }

    // --- take-first-success ------------------------------------------------

    /**
     * The whole point: three providers asked at once, and the answer arrives in
     * about the time of the ONE that has it - not the sum of all three, and not
     * the time of the slowest.
     */
    @Test
    void theFanoutIsBoundedByTheProviderThatAnswersRatherThanBySlowestOrSum() {
        middling.holds(REFERENCE);

        long startedAt = System.nanoTime();
        Optional<PspAdapter.ProviderLookup> found = fanoutOf(fast, middling, slow)
                .firstToClaim(REFERENCE);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(found).get()
                .satisfies(lookup -> assertThat(lookup.pspId()).isEqualTo("middling"));

        // Sequentially this is 20 + 120 = 140ms at best and 390ms at worst.
        // In parallel it is the 120ms one, plus overhead.
        assertThat(elapsedMs)
                .as("a sequential implementation would take at least 140ms")
                .isLessThan(120L + 80L);
    }

    /**
     * THE SUBTLE ONE. A provider that does NOT have the reference answers
     * fastest of all - it does no work - so a naive first-to-return race is won
     * by the wrong provider every single time.
     *
     * <p>Here the only provider holding the payment is also the slowest. If
     * "not found" were treated as a successful result, the scope would take the
     * 20ms "no" from {@code fast}, cancel {@code slow} mid-flight, and report
     * that nobody has a payment somebody does.
     */
    @Test
    void aProviderThatDoesNotHaveItCannotWinTheRace() {
        slow.holds(REFERENCE);

        Optional<PspAdapter.ProviderLookup> found = fanoutOf(fast, middling, slow)
                .firstToClaim(REFERENCE);

        assertThat(found).get()
                .satisfies(lookup -> assertThat(lookup.pspId()).isEqualTo("slow"));
    }

    /**
     * The others are cancelled once one claims it. Structured concurrency does
     * this on the way out of the try-with-resources, which is the property an
     * ExecutorService does not give: an abandoned task there keeps its
     * connection open against a provider nobody is waiting for.
     */
    @Test
    void theRemainingProvidersAreCancelledOnceOneClaimsTheReference() {
        fast.holds(REFERENCE);

        fanoutOf(fast, middling, slow).firstToClaim(REFERENCE);

        assertThat(slow.completed.get())
                .as("the slow provider must have been interrupted, not waited for")
                .isFalse();
    }

    @Test
    void nobodyClaimingTheReferenceIsAnEmptyAnswerRatherThanAFailure() {
        assertThat(fanoutOf(fast, middling, slow).firstToClaim(REFERENCE)).isEmpty();
    }

    // --- ask-everyone ------------------------------------------------------

    @Test
    void askingEveryoneCollectsOneAnswerPerProvider() {
        middling.holds(REFERENCE);

        StatusFanout.FanoutResult result = fanoutOf(fast, middling, slow).askEveryone(REFERENCE);

        assertThat(result.answers()).hasSize(3);
        assertThat(result.silent()).isEmpty();
        assertThat(result.claimed()).get()
                .satisfies(lookup -> assertThat(lookup.pspId()).isEqualTo("middling"));
    }

    /**
     * ONE PROVIDER DOWN IS THE NORMAL STATE OF A THREE-PROVIDER SYSTEM, and the
     * fan-out must not abandon two good answers to report one bad one.
     *
     * <p>An all-or-nothing joiner that cancelled on the first failure would make
     * reconciliation refuse to run whenever any provider was unhealthy - which
     * is to say, refuse to run.
     */
    @Test
    void oneProviderFailingDoesNotDiscardTheOthersAnswers() {
        middling.holds(REFERENCE);
        slow.failsWith(new IllegalStateException("provider down"));

        StatusFanout.FanoutResult result = fanoutOf(fast, middling, slow).askEveryone(REFERENCE);

        assertThat(result.answers())
                .as("the two working providers still answered")
                .hasSize(2);
        assertThat(result.silent()).containsExactly("slow");
        assertThat(result.claimed()).isPresent();
    }

    /**
     * THE ASSERTION THAT PREVENTS A DOUBLE CHARGE.
     *
     * <p>"Nobody has this payment" is only safe to conclude when every provider
     * was asked and every one of them said no. A provider that failed said
     * nothing, and silence is not a no - re-authorizing on the strength of it
     * would charge a customer who may already have been charged.
     */
    @Test
    void nobodyHasItIsFalseWhileAnyProviderStayedSilent() {
        slow.failsWith(new IllegalStateException("provider down"));

        StatusFanout.FanoutResult result = fanoutOf(fast, middling, slow).askEveryone(REFERENCE);

        assertThat(result.answers()).allSatisfy(a -> assertThat(a.found()).isFalse());
        assertThat(result.nobodyHasIt())
                .as("two noes and a silence is not the same as three noes")
                .isFalse();
    }

    @Test
    void nobodyHasItIsTrueOnlyWhenEveryProviderAnsweredNo() {
        StatusFanout.FanoutResult result = fanoutOf(fast, middling, slow).askEveryone(REFERENCE);

        assertThat(result.silent()).isEmpty();
        assertThat(result.nobodyHasIt()).isTrue();
    }

    // --- the deadline ------------------------------------------------------

    /**
     * THE PROPERTY THAT MAKES StructuredTaskScope THE RIGHT TOOL.
     *
     * <p>{@link Deadlines} is a {@code ScopedValue}, visible to the thread that
     * bound it and to threads forked inside a structured scope - and to nothing
     * else. Hand the same work to a shared executor and {@code current()} comes
     * back empty on the far side, so every provider call runs unbounded, which
     * is the failure phase 3a exists to remove.
     *
     * <p>This asserts it from inside a forked subtask, because that is the only
     * place the claim can actually be checked.
     */
    @Test
    void theRequestDeadlineIsVisibleInsideEachForkedProviderCall() throws Exception {
        AtomicBoolean sawDeadline = new AtomicBoolean();
        StubAdapter observer = new StubAdapter("observer", 5);
        observer.onLookup(() -> sawDeadline.set(Deadlines.current().isPresent()));

        assertThat(Deadlines.current())
                .as("nothing bound out here, so an empty result inside would prove nothing")
                .isEmpty();

        Deadlines.runWith(Deadline.of(5_000), () ->
                fanoutOf(observer).askEveryone(REFERENCE));

        assertThat(sawDeadline)
                .as("the deadline must travel into the fork, or the provider calls are unbounded")
                .isTrue();
    }

    /**
     * A request with almost nothing left still gets a floor, rather than opening
     * a scope and timing it out immediately - paying the whole cost of the
     * fan-out to learn nothing.
     */
    @Test
    void anAlmostExpiredDeadlineStillAllowsAMinimumFanout() throws Exception {
        fast.holds(REFERENCE);

        Optional<PspAdapter.ProviderLookup> found = Deadlines.runWith(Deadline.of(1), () ->
                fanoutOf(fast).firstToClaim(REFERENCE));

        assertThat(found).isPresent();
    }

    /** No providers configured is an empty answer, not an exception. */
    @Test
    void noProvidersAtAllIsAnsweredRatherThanThrown() {
        assertThat(fanoutOf().firstToClaim(REFERENCE)).isEmpty();
        assertThat(fanoutOf().askEveryone(REFERENCE).answers()).isEmpty();
    }

    // --- doubles -----------------------------------------------------------

    /** A registry over a fixed set of adapters, with no config store behind it. */
    private static final class StubRegistry extends PspAdapterRegistry {

        private final Set<String> ids;
        private final java.util.Map<String, PspAdapter> byId;

        StubRegistry(Set<String> ids, java.util.Map<String, PspAdapter> byId) {
            super(null, List.of(), pspId -> {
                throw new IllegalStateException("no factory in this test");
            });
            this.ids = ids;
            this.byId = byId;
        }

        @Override
        public Set<String> routable() {
            return ids;
        }

        @Override
        public PspAdapter require(String pspId) {
            return byId.get(pspId);
        }
    }

    /**
     * A provider that takes a known amount of time to answer.
     *
     * <p>{@code completed} is the interesting field: it stays false when the
     * scope cancels this adapter mid-sleep, which is how the cancellation test
     * can tell "was not waited for" from "answered quickly".
     */
    private static final class StubAdapter implements PspAdapter {

        private final String pspId;
        private final long delayMs;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean();

        private volatile String heldReference;
        private volatile RuntimeException failure;
        private volatile Runnable onLookup;

        StubAdapter(String pspId, long delayMs) {
            this.pspId = pspId;
            this.delayMs = delayMs;
        }

        void holds(String reference) {
            this.heldReference = reference;
        }

        void failsWith(RuntimeException failure) {
            this.failure = failure;
        }

        void onLookup(Runnable action) {
            this.onLookup = action;
        }

        @Override
        public String pspId() {
            return pspId;
        }

        @Override
        public ProviderLookup lookup(LookupCommand command) {
            calls.incrementAndGet();
            if (onLookup != null) {
                onLookup.run();
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                // Cancelled by the scope. Restore the flag and leave `completed`
                // false, which is exactly what the cancellation test reads.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cancelled", e);
            }
            completed.set(true);
            if (failure != null) {
                throw failure;
            }
            return command.reference().equals(heldReference)
                    ? new ProviderLookup(pspId, true, "ref_" + pspId, "APPROVED", false, false, 4200)
                    : ProviderLookup.notFound(pspId);
        }

        @Override
        public ProviderAuthorization authorize(AuthorizeCommand command, DetokenizedCard card) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public ProviderCapture capture(CaptureCommand command) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public ProviderReversal reverse(ReverseCommand command) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
