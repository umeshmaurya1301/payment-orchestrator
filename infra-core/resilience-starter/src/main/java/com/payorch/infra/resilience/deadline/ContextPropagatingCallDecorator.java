package com.payorch.infra.resilience.deadline;

import java.util.concurrent.Callable;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * Carries thread-bound context across {@link DeadlineExecutor}'s handoff.
 *
 * <p>Uses Micrometer's context-propagation library rather than reaching for
 * OpenTelemetry's {@code Context} directly, which matters for one reason: it
 * captures <em>everything registered</em>, not only tracing. The OTel context is
 * what phase 4 needed, but MDC travels through the same mechanism, and a
 * hand-rolled trace-only version would have left the log fields behind and
 * produced correlated traces attached to uncorrelated logs - which is arguably
 * worse than neither, because it looks like it works.
 *
 * <p>The snapshot is taken on the <strong>calling</strong> thread, in
 * {@link #decorate}, and restored inside the wrapped call on the executor's
 * thread. Capturing inside the {@code Callable} would capture the wrong thread's
 * context - which is to say nothing at all, since that thread is fresh - and is
 * the obvious way to write this and have it silently do nothing.
 *
 * <p>Restoration is scoped and closed, so the executor's thread does not keep
 * the caller's context after the call. Virtual threads are not pooled, so a leak
 * here would be harmless today and a genuine cross-request context bleed the
 * moment anything is pooled - the same reasoning that puts an MDC {@code remove}
 * in a {@code finally} in the auth filter.
 */
public class ContextPropagatingCallDecorator implements CallDecorator {

    private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

    @Override
    public <T> Callable<T> decorate(Callable<T> call) {
        // Captured here: on the caller's thread, at submit time.
        ContextSnapshot snapshot = snapshots.captureAll();
        return () -> {
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                return call.call();
            }
        };
    }
}
