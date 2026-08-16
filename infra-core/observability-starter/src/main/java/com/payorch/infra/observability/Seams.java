package com.payorch.infra.observability;

import java.util.concurrent.Callable;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Manual spans on the seams auto-instrumentation cannot name.
 *
 * <p>OpenTelemetry's auto-instrumentation gives HTTP and JDBC for free, and that
 * is genuinely most of a trace. What it cannot give is meaning: it will show a
 * {@code POST /psp/v1/authorize} taking 2.5 s and a {@code SELECT} taking 3 ms,
 * and nothing anywhere says which of those was a detokenization, which was the
 * state transition that made the payment real, or which provider was being
 * called. A trace made only of transport is a waterfall you can read and cannot
 * interpret.
 *
 * <p>So: four seams, hand-written, and deliberately only four.
 *
 * <ul>
 *   <li><strong>provider call</strong> - the one that costs money and time</li>
 *   <li><strong>tokenization</strong> - where a card number stops existing</li>
 *   <li><strong>detokenization</strong> - where it briefly exists again</li>
 *   <li><strong>state transition</strong> - where the payment's meaning changes</li>
 * </ul>
 *
 * <p>The trap on the other side is worse than under-instrumenting: a span per
 * method produces traces nobody can read and a storage bill nobody wants. If a
 * span would not change what someone did next, it is noise wearing the costume
 * of rigour.
 *
 * <h2>What goes on a span, and what does not</h2>
 *
 * <p>{@code paymentId} belongs here. It does <strong>not</strong> belong on a
 * metric: one time series per payment is a cardinality explosion that takes the
 * storage down, and it is the single most common way a well-meaning
 * instrumentation change becomes an outage. Traces and logs are high-cardinality
 * stores by design; metrics are not. Same identifier, different homes, and the
 * difference is not stylistic.
 *
 * <p>Card data has no home at all. There is no overload here that takes a PAN,
 * and the phase-4 leak test scans exported spans as well as log lines.
 */
public class Seams {

    public static final String PROVIDER_CALL = "psp.authorize";
    public static final String TOKENIZE = "vault.tokenize";
    public static final String DETOKENIZE = "vault.detokenize";
    public static final String STATE_TRANSITION = "payment.transition";

    private final Tracer tracer;

    public Seams(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Runs {@code work} inside a span, tagged and timed.
     *
     * <p>The span is closed in a {@code finally} and errors are recorded on it
     * before it ends. A span that is only ended on the happy path leaves the
     * trace showing the operation as still running - which, in a system whose
     * whole point is telling {@code FAILED} from {@code UNKNOWN}, is exactly the
     * wrong story to tell about a call that failed.
     */
    public <T> T inSpan(String name, Callable<T> work, String... tagPairs) throws Exception {
        Span span = tracer.nextSpan().name(name);
        for (int i = 0; i + 1 < tagPairs.length; i += 2) {
            span.tag(tagPairs[i], tagPairs[i + 1]);
        }
        span.start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.call();
        } catch (Exception e) {
            // The exception type, not the message. A message can carry a
            // reference, an amount, or whatever a provider decided to put in it;
            // the class name answers "what went wrong" without becoming another
            // place card data could surface.
            span.tag("error", e.getClass().getSimpleName());
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** For seams that return nothing - a state transition, say. */
    public void inSpan(String name, Runnable work, String... tagPairs) {
        try {
            inSpan(name, () -> {
                work.run();
                return null;
            }, tagPairs);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The current trace id, for a response header or a support conversation. */
    public String currentTraceId() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context().traceId();
    }
}
