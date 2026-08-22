package com.payorch.connector.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.infra.logging.LogEvent;
import org.infra.logging.LogFields;
import org.infra.resilience.deadline.Deadlines;

/**
 * Asks every provider about one reference, at the same time. Phase 7f.
 *
 * <h2>What this is for</h2>
 *
 * <p>A payment recorded {@code UNKNOWN}. The request may or may not have
 * arrived, no {@code providerRef} ever came back, and the only question left is
 * "does anybody have this?" - which has to be asked of every provider, because
 * phase 5's failover means it might not be the one we think.
 *
 * <p>Sequentially that is three round trips, and the third one is paid for by a
 * customer waiting on a resolution. In parallel it is one round trip, bounded by
 * the slowest provider rather than by their sum.
 *
 * <h2>Why {@code StructuredTaskScope} and not an ExecutorService</h2>
 *
 * <p>{@code invokeAll} and {@code invokeAny} do most of this, and the difference
 * is what happens when things go wrong.
 *
 * <ul>
 *   <li><strong>Cancellation is automatic and complete.</strong> When the scope
 *       closes - normally, by exception, or because the calling thread was
 *       interrupted - every subtask still running is cancelled and joined before
 *       {@code close()} returns. There is no path out of the try-with-resources
 *       that leaves a provider call running behind it. With an executor, a task
 *       submitted and then abandoned keeps a connection open against a provider
 *       nobody is waiting for any more, which is exactly the leak that turns a
 *       slow provider into an exhausted pool.</li>
 *   <li><strong>The deadline travels with the fork.</strong> {@link Deadlines}
 *       is a {@code ScopedValue}, and a scoped value is visible to the thread
 *       that bound it <em>and to threads forked inside a
 *       StructuredTaskScope</em> - and to nothing else. Hand the same work to a
 *       shared executor and {@code Deadlines.current()} comes back empty on the
 *       far side, so every provider call runs unbounded. That is not a
 *       hypothetical: it is why {@code DeadlineExecutor} has to capture the
 *       deadline explicitly before submitting.</li>
 *   <li><strong>The failure is not one thread's problem.</strong> A subtask that
 *       throws propagates to the owner at {@code join}, rather than into a
 *       {@code Future} nobody remembered to check.</li>
 * </ul>
 *
 * <h2>Both shapes, because they answer different questions</h2>
 *
 * <p>{@link #firstToClaim} takes the first provider that says yes and cancels
 * the rest - "any provider can answer". {@link #askEveryone} waits for all of
 * them - "I need every result". They are not interchangeable, and using the
 * wrong one is a real bug rather than a style choice: see the javadoc on each.
 *
 * <h2>The API shape is pinned, deliberately</h2>
 *
 * <p>{@code StructuredTaskScope} is a preview API and has already changed twice.
 * The {@code ShutdownOnSuccess} / {@code ShutdownOnFailure} subclasses that most
 * tutorials still show do not exist in JDK 25; the entry point is
 * {@code StructuredTaskScope.open(Joiner...)}. See this module's
 * {@code build.gradle.kts} for what {@code --enable-preview} costs at deploy
 * time, which is more than it costs at compile time.
 */
@Service
public class StatusFanout {

    private static final Logger log = LoggerFactory.getLogger(StatusFanout.class);

    /**
     * A floor under the fan-out, independent of the request's deadline.
     *
     * <p>Without it a request arriving with 5ms left would open a scope, fork
     * three provider calls and immediately time them out - paying the full cost
     * of the fan-out to learn nothing. The scope's own timeout is the smaller of
     * this and what the deadline actually allows.
     */
    private static final long MIN_FANOUT_MS = 50;

    private final PspAdapterRegistry adapters;

    /**
     * Used only outside a request scope, where there is no deadline to consult.
     *
     * <p>Falling back rather than throwing, for the reason
     * {@code Deadlines.currentOrDefault} exists: an unbounded call is the
     * failure this whole layer removes, so a scheduled job or a test should
     * still be bounded by something rather than by nothing.
     */
    private final long fallbackMs;

    /**
     * One constructor, not two.
     *
     * <p>A convenience overload looks harmless and is not: Spring cannot choose
     * between two unannotated constructors and fails the whole context with
     * "No default constructor found", which names neither of them. Measured -
     * this class shipped with both for exactly one compile.
     */
    public StatusFanout(PspAdapterRegistry adapters,
                        @Value("${payorch.connector.fanout.fallback-ms:5000}") long fallbackMs) {
        this.adapters = adapters;
        this.fallbackMs = fallbackMs;
    }

    /**
     * The first provider that admits to having this reference.
     *
     * <h2>When this is the right question</h2>
     *
     * <p>A reference belongs to at most one provider - it is the idempotency key
     * we sent them - so as soon as one says yes, the others cannot add anything
     * and are cancelled mid-flight. That is the strongest form of this: the
     * answer is bounded by the FASTEST provider that has it, not the slowest
     * provider overall.
     *
     * <h2>When it is the wrong question, which matters more</h2>
     *
     * <p>Never use this to decide that <strong>nobody</strong> has it. An empty
     * result here means "no provider answered yes before the deadline", which is
     * not the same as "no provider holds this payment" - one of them may have
     * been slow, or its breaker open, and the caller cannot tell those apart.
     * Concluding "not found" from a first-success fan-out and then re-authorizing
     * would be a double charge derived from a timeout.
     *
     * <p>{@link #askEveryone} is the one that can answer "nobody has it", because
     * it distinguishes a provider that said no from a provider that said nothing.
     */
    public Optional<PspAdapter.ProviderLookup> firstToClaim(String reference) {
        List<PspAdapter> targets = targets();
        if (targets.isEmpty()) {
            return Optional.empty();
        }

        Instant startedAt = Instant.now();
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<PspAdapter.ProviderLookup>anySuccessfulResultOrThrow(),
                cfg -> cfg.withTimeout(budget()))) {

            for (PspAdapter adapter : targets) {
                scope.fork(() -> {
                    PspAdapter.ProviderLookup found = adapter.lookup(
                            new PspAdapter.LookupCommand(reference));
                    if (!found.found()) {
                        // A "no" must not win the race. anySuccessfulResultOrThrow
                        // takes the first subtask to RETURN, and a provider that
                        // correctly reports it has never seen this reference
                        // returns fastest of all - it does no work. Returning
                        // normally here would cancel the provider that actually
                        // holds the payment, so not-found is expressed as a
                        // failed subtask and the joiner passes over it.
                        throw new NotHere(adapter.pspId());
                    }
                    return found;
                });
            }

            PspAdapter.ProviderLookup claimed = scope.join();
            log.info("provider fan-out resolved a reference",
                    LogEvent.event()
                            .with(LogFields.PSP_ID, claimed.pspId())
                            .with(LogFields.OPERATION, "lookup")
                            .with(LogFields.OUTCOME, "FOUND")
                            .with(LogFields.LATENCY_MS,
                                    Duration.between(startedAt, Instant.now()).toMillis())
                            .args());
            return Optional.of(claimed);

        } catch (StructuredTaskScope.FailedException e) {
            // Every subtask failed, which here mostly means every provider said
            // no. DEBUG rather than WARN: for a reference that was never sent
            // anywhere, this is the correct and expected answer.
            log.debug("no provider claimed reference after {}ms",
                    Duration.between(startedAt, Instant.now()).toMillis());
            return Optional.empty();
        } catch (StructuredTaskScope.TimeoutException e) {
            // NOT the same as "nobody has it", and the caller must not read it
            // that way. See the class javadoc.
            log.warn("provider fan-out timed out before any provider claimed the reference",
                    LogEvent.event()
                            .with(LogFields.OPERATION, "lookup")
                            .with(LogFields.OUTCOME, "TIMEOUT")
                            .args());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Every provider's answer, including the ones that failed.
     *
     * <h2>Why this does not stop at the first failure</h2>
     *
     * <p>The obvious all-or-nothing joiner - cancel everything as soon as one
     * subtask throws - is wrong for this operation, and the reason is worth
     * being precise about. One provider being down is the NORMAL state of a
     * three-provider system; it is what phases 3 and 5 exist to survive.
     * Abandoning the fan-out because of it would throw away two good answers to
     * report one bad one, and a reconciliation that refuses to run whenever any
     * provider is unhealthy is a reconciliation that never runs.
     *
     * <p>So every subtask is allowed to finish and the result says explicitly
     * which providers answered. A caller deciding "nobody has this payment" can
     * then check that <em>every</em> provider actually said no, rather than
     * inferring it from silence - which is the distinction {@link #firstToClaim}
     * cannot make and the reason both methods exist.
     *
     * @return one entry per provider asked, in a stable order
     */
    public FanoutResult askEveryone(String reference) {
        List<PspAdapter> targets = targets();
        if (targets.isEmpty()) {
            return new FanoutResult(List.of(), List.of());
        }

        Instant startedAt = Instant.now();
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<PspAdapter.ProviderLookup>allUntil(subtask -> false),
                cfg -> cfg.withTimeout(budget()))) {

            List<String> asked = new ArrayList<>();
            for (PspAdapter adapter : targets) {
                asked.add(adapter.pspId());
                scope.fork(() -> adapter.lookup(new PspAdapter.LookupCommand(reference)));
            }

            List<PspAdapter.ProviderLookup> answers = new ArrayList<>();
            List<String> silent = new ArrayList<>(asked);

            scope.join().forEach(subtask -> {
                if (subtask.state() == Subtask.State.SUCCESS) {
                    PspAdapter.ProviderLookup answer = subtask.get();
                    answers.add(answer);
                    silent.remove(answer.pspId());
                } else if (subtask.state() == Subtask.State.FAILED) {
                    // WHY A FAILED SUBTASK IS LOGGED AT ALL.
                    //
                    // It was not, and that made this class silent about its own
                    // silence. `silent` is the field every caller uses to decide
                    // whether "nobody has it" is safe to conclude, and it was
                    // populated by discarding the exception that explained it.
                    //
                    // Found in phase 8a: the UNKNOWN poller reported 250 polls
                    // and 250 inconclusive results, with all four providers
                    // silent on every one, while those same providers answered a
                    // direct HTTP call correctly. No breaker was open, no
                    // bulkhead rejecting and no deadline declining - and there
                    // was no line anywhere saying what had actually thrown,
                    // because this branch did not exist.
                    //
                    // The exception TYPE, not the message: a provider reference
                    // can appear in a message and this line is written on every
                    // failed lookup.
                    Throwable cause = subtask.exception();
                    log.warn("a provider did not answer a lookup",
                            LogEvent.event()
                                    .with(LogFields.OPERATION, "lookup")
                                    .with(LogFields.OUTCOME, "SILENT")
                                    .with(LogFields.ERROR_CODE,
                                            cause == null ? "unknown" : cause.getClass().getSimpleName())
                                    .args());
                }
            });

            answers.sort(Comparator.comparing(PspAdapter.ProviderLookup::pspId));
            log.info("provider fan-out completed",
                    LogEvent.event()
                            .with(LogFields.OPERATION, "lookup")
                            .with(LogFields.OUTCOME, silent.isEmpty() ? "COMPLETE" : "PARTIAL")
                            .with(LogFields.LATENCY_MS,
                                    Duration.between(startedAt, Instant.now()).toMillis())
                            .args());
            return new FanoutResult(List.copyOf(answers), List.copyOf(silent));

        } catch (StructuredTaskScope.TimeoutException e) {
            log.warn("provider fan-out timed out with no complete answer");
            return new FanoutResult(List.of(), adapters.routable().stream().sorted().toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FanoutResult(List.of(), adapters.routable().stream().sorted().toList());
        }
    }

    /**
     * What every provider said, and which of them said nothing.
     *
     * <p>The second list is the point. "No provider holds this payment" is only
     * safe to conclude when {@code silent} is empty - otherwise the answer is
     * "the providers that answered do not hold it", which is a different and
     * much weaker statement, and acting on the strong one would re-authorize a
     * payment that may already exist.
     *
     * @param answers one per provider that responded, sorted by pspId
     * @param silent  providers that failed, timed out, or were not asked because
     *                their breaker was open
     */
    public record FanoutResult(List<PspAdapter.ProviderLookup> answers, List<String> silent) {

        /** The provider that holds this reference, if exactly one claims it. */
        public Optional<PspAdapter.ProviderLookup> claimed() {
            return answers.stream().filter(PspAdapter.ProviderLookup::found).findFirst();
        }

        /**
         * True only when every provider was asked, every one answered, and every
         * one said no.
         */
        public boolean nobodyHasIt() {
            return silent.isEmpty() && answers.stream().noneMatch(PspAdapter.ProviderLookup::found);
        }
    }

    /**
     * How long the fan-out may take.
     *
     * <p>From the request's remaining deadline, not a constant - the same
     * argument as the idempotency wait budget in 7b. A fan-out that outlived its
     * caller would be writing an answer to a connection nobody is reading, and
     * it would be the one unbounded thing in a service built entirely around
     * bounds.
     */
    private Duration budget() {
        // currentOrDefault, NOT current().orElse(MIN_FANOUT_MS), and the
        // difference is a bug this method had for one commit. The minimum is a
        // FLOOR for a request that has nearly run out of time; using it as the
        // FALLBACK for code reached outside a request scope - a reconciliation
        // job, a test - gives that code 50ms to hear from three providers, so
        // every one of them times out and the fan-out reports that nobody holds
        // a payment somebody does.
        //
        // Two different questions that happen to want a number: "how little is
        // it worth starting with" and "what if nobody said". Deadlines has the
        // same distinction and answers it the same way.
        long remaining = Deadlines.currentOrDefault(fallbackMs).remainingMs();
        return Duration.ofMillis(Math.max(MIN_FANOUT_MS, remaining));
    }

    /**
     * The providers worth asking.
     *
     * <p>{@code routable()} rather than every configured adapter, so a provider
     * whose breaker is open is not asked at all. Asking it would cost the
     * fan-out nothing in latency - the breaker refuses immediately - but it
     * would appear in {@code silent}, which is exactly the field callers use to
     * decide whether "nobody has it" is safe to conclude. A provider that is
     * disabled today and has never seen this reference should not make that
     * question unanswerable forever.
     */
    private List<PspAdapter> targets() {
        return adapters.routable().stream()
                .sorted()
                .map(adapters::require)
                .toList();
    }

    /**
     * A provider reporting that it has never seen the reference.
     *
     * <p>An exception, and only because {@code anySuccessfulResultOrThrow} races
     * on <em>returning</em>: a provider that does not hold the payment answers
     * fastest, and letting it win would cancel the provider that does. Not an
     * error in any other sense - it is never logged as one and never escapes
     * this class.
     */
    private static final class NotHere extends RuntimeException {

        NotHere(String pspId) {
            // No stack trace: this is control flow across a handful of subtasks
            // on every reconciliation, and filling in a trace for each would be
            // pure cost for something nobody will ever read.
            super(pspId + " has no record of this reference", null, false, false);
        }
    }
}
