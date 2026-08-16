package com.payorch.infra.resilience.deadline;

import java.util.concurrent.Callable;

/**
 * Wraps work that is about to cross a thread handoff.
 *
 * <p>{@link DeadlineExecutor} runs every downstream call on a fresh virtual
 * thread, because that is the only way to abort one - see its javadoc. The cost
 * of that handoff is that <strong>nothing thread-bound travels</strong>, and
 * this project has now paid it three times:
 *
 * <ul>
 *   <li>3a: the deadline is a {@code ScopedValue}, re-bound explicitly.</li>
 *   <li>3d: {@code ThreadPoolBulkhead} re-binds the same thing across its pool.</li>
 *   <li>4: the trace context is an OpenTelemetry {@code Context} in a
 *       ThreadLocal, and it silently did not travel at all.</li>
 * </ul>
 *
 * <p>The third was the expensive one to find, because it does not fail. The
 * outbound call simply finds no active span, starts a <em>new root trace</em>,
 * and propagates that instead - so every service in a four-hop payment gets its
 * own trace id and the result reads as "distributed tracing is broken" rather
 * than "a ThreadLocal did not cross a handoff". Four traces of one span each
 * look exactly like four unrelated requests.
 *
 * <p>Hence this seam: a hook the resilience layer calls without knowing what is
 * being carried, so tracing can be restored across the handoff without
 * resilience-starter growing a dependency on a tracing library.
 */
@FunctionalInterface
public interface CallDecorator {

    <T> Callable<T> decorate(Callable<T> call);

    /**
     * The default. Deliberately a no-op rather than absent, so a service without
     * context propagation behaves identically to one that has never heard of it
     * rather than needing a null check on the hot path.
     */
    CallDecorator NONE = new CallDecorator() {
        @Override
        public <T> Callable<T> decorate(Callable<T> call) {
            return call;
        }
    };
}
