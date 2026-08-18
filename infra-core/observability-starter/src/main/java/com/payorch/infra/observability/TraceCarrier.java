package com.payorch.infra.observability;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Trace context as a value you can write down.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Trace context normally lives in a {@code ThreadLocal} and is propagated by
 * instrumentation nobody has to think about: an HTTP client injects it into
 * request headers, an HTTP server extracts it back out, and a four-service trace
 * assembles itself. That works because the two ends of every hop are joined by a
 * call that is happening <em>right now</em>, on a thread that still has the
 * context on it.
 *
 * <p>The outbox breaks both halves of that. The event is created on a request
 * thread inside the payment's transaction, and it is published later - after a
 * commit, after a poll interval, on a scheduler thread, possibly after a
 * restart, and in the CDC arm by a process that is not this JVM at all. By the
 * time anything talks to Kafka there is no ambient context left to propagate,
 * because the request it belonged to finished seconds or minutes ago.
 *
 * <p>So the context has to stop being a thread-local and become a
 * <strong>column</strong>: captured at the moment the event is recorded, in the
 * same transaction, and read back when the event is finally published. Which is
 * the outbox's own argument - <em>if two facts must agree, one transaction has
 * to write both</em> - applied to the trace rather than to the payload.
 *
 * <h2>W3C {@code traceparent}, and why the format is not hand-rolled</h2>
 *
 * <p>The stored value is whatever the configured {@link Propagator} produces,
 * which for this project's {@code management.tracing.propagation.type=W3C} is a
 * 55-character {@code traceparent}. It would be four lines to format that string
 * by hand from a {@code TraceContext}, and those four lines would silently
 * become wrong the day somebody switches the propagation type to B3 - the
 * services would agree with each other over HTTP and disagree across Kafka, so
 * traces would break at one hop and only for asynchronous work. Going through
 * the propagator means the header written into a Kafka record and the header
 * written into an HTTP request are produced by the same object.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not restore the original span as "current". A span that ended
 * minutes ago cannot be resumed, and pretending otherwise produces spans with
 * impossible durations. {@link #continuing} starts a <em>new child</em> of the
 * stored context, so the published record joins the original trace as a
 * descendant - which is what a messaging producer span is meant to be.
 */
public class TraceCarrier {

    /** The W3C header name. Also the Kafka header name, deliberately - see the class javadoc. */
    public static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceCarrier(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * Work that runs with the headers carrying the trace onward.
     *
     * <p>Takes the headers rather than reading them from a thread-local,
     * because the entire point of this class is the cases where a thread-local
     * would be empty or wrong.
     */
    @FunctionalInterface
    public interface Carried<T> {
        T apply(Map<String, String> headers) throws Exception;
    }

    /**
     * The current trace context as a {@code traceparent} string, or {@code null}
     * when nothing is being traced.
     *
     * <p>Null is a normal answer, not a failure: sampling is a decision the
     * tracer is allowed to make, and a service running without an exporter has
     * no context to capture. A caller storing this should store the null.
     */
    public String capture() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>(2);
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Runs {@code work} inside a new span whose parent is {@code stored}, and
     * hands it the headers that carry that new span onward.
     *
     * <p>Two-step on purpose. The headers describe the span created <em>here</em>,
     * not the stored one, so the consumer's span becomes a child of the publish
     * rather than a sibling of the original request - the waterfall then reads
     * request &rarr; publish &rarr; consume, in that order, with the relay lag
     * visible as the gap between the second and the third.
     *
     * <p>When {@code stored} is null the work still runs, without a span and
     * with empty headers. That is the untraced case, and it must stay cheap and
     * silent: the outbox rows written before this column existed have no
     * context, and a service running with tracing off has none either. Neither
     * is an error, and neither should produce an orphan root span per event.
     */
    public <T> T continuing(String stored, String spanName, Carried<T> work) throws Exception {
        if (stored == null || stored.isBlank()) {
            return work.apply(Map.of());
        }

        Map<String, String> in = Map.of(TRACEPARENT, stored);
        Span span = propagator.extract(in, Map::get)
                .name(spanName)
                .kind(Span.Kind.PRODUCER)
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Map<String, String> out = new HashMap<>(2);
            propagator.inject(span.context(), out, Map::put);
            return work.apply(out);
        } catch (Exception e) {
            // The class name, not the message. Same rule as Seams: a message can
            // carry a reference, an amount, or whatever a provider put in it.
            span.tag("error", e.getClass().getSimpleName());
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
