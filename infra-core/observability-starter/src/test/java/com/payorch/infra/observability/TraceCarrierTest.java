package com.payorch.infra.observability;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the outbox needs from a trace context, tested against a real SDK.
 *
 * <p>A mock tracer would pass every assertion here and prove nothing: the claim
 * is about the exact bytes of a W3C {@code traceparent} surviving a round trip
 * through a database column, and a stub that returns whatever it was handed
 * cannot fail that. So this builds an actual OpenTelemetry SDK with the real
 * W3C propagator - the same two objects Boot's autoconfiguration builds - and
 * asserts on the trace ids that come out.
 */
class TraceCarrierTest {

    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    // Always sample. An unsampled span yields a traceparent
                    // ending in -00 and a consumer that correctly ignores it,
                    // which would make this test flaky for a legitimate reason.
                    .setSampler(Sampler.alwaysOn())
                    .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

    private final OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
    private final Tracer tracer = new OtelTracer(
            sdk.getTracer("payorch-test"), currentTraceContext, event -> { });
    private final Propagator propagator = new OtelPropagator(
            sdk.getPropagators(), sdk.getTracer("payorch-test"));

    private final TraceCarrier carrier = new TraceCarrier(tracer, propagator);

    /**
     * The round trip the outbox actually performs: capture on the request
     * thread, store a string, continue from that string somewhere else. The
     * trace id has to be the same on both sides or the ledger's spans land in a
     * trace of their own, which is the failure this class was written for.
     */
    @Test
    void aCapturedContextContinuesTheSameTrace() {
        Span request = tracer.nextSpan().name("POST /v1/payments").start();
        String stored;
        String originalTraceId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            originalTraceId = request.context().traceId();
            stored = carrier.capture();
        } finally {
            request.end();
        }

        assertThat(stored)
                .as("a W3C traceparent, which is what the column stores")
                .startsWith("00-" + originalTraceId + "-");

        AtomicReference<Map<String, String>> handed = new AtomicReference<>();
        AtomicReference<String> traceIdInsideTheSpan = new AtomicReference<>();

        assertThatNoCheckedException(() -> carrier.continuing(stored, "outbox publish", headers -> {
            handed.set(headers);
            traceIdInsideTheSpan.set(tracer.currentSpan().context().traceId());
            return null;
        }));

        assertThat(traceIdInsideTheSpan.get())
                .as("the publish must be IN the request's trace, not merely aware of it")
                .isEqualTo(originalTraceId);
        assertThat(handed.get()).containsKey(TraceCarrier.TRACEPARENT);
        assertThat(handed.get().get(TraceCarrier.TRACEPARENT))
                .as("the headers going onto the record carry the same trace")
                .startsWith("00-" + originalTraceId + "-");
    }

    /**
     * The headers describe the publish, not the request that caused it. If they
     * were the stored string echoed back, the consumer's span would be a sibling
     * of the HTTP span rather than a child of the publish, and the relay lag -
     * the whole point of comparing the polling relay against CDC - would be
     * invisible in the waterfall.
     */
    @Test
    void theInjectedHeaderIdentifiesTheNewSpanRatherThanTheStoredOne() {
        Span request = tracer.nextSpan().name("POST /v1/payments").start();
        String stored;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            stored = carrier.capture();
        } finally {
            request.end();
        }

        AtomicReference<String> injected = new AtomicReference<>();
        assertThatNoCheckedException(() -> carrier.continuing(stored, "outbox publish", headers -> {
            injected.set(headers.get(TraceCarrier.TRACEPARENT));
            return null;
        }));

        assertThat(injected.get()).isNotEqualTo(stored);
        assertThat(traceIdOf(injected.get())).isEqualTo(traceIdOf(stored));
        assertThat(spanIdOf(injected.get())).isNotEqualTo(spanIdOf(stored));
    }

    /**
     * Rows written before V11 have no traceparent, and a service running with
     * tracing off never captures one. Both are normal. The work still has to
     * run, and it must not gain an orphan root span per event - a trace
     * containing nothing but "outbox publish" is noise that costs storage and
     * answers no question.
     */
    @Test
    void anEventWithNoStoredContextStillPublishes() {
        AtomicReference<Map<String, String>> handed = new AtomicReference<>();

        assertThatNoCheckedException(() ->
                carrier.continuing(null, "outbox publish", headers -> {
                    handed.set(headers);
                    return "sent";
                }));

        assertThat(handed.get()).isEmpty();
        assertThat(tracer.currentSpan()).isNull();
    }

    /** Same for a column that exists but holds an empty string. */
    @Test
    void aBlankStoredContextIsTreatedAsAbsent() {
        AtomicReference<Map<String, String>> handed = new AtomicReference<>();
        assertThatNoCheckedException(() ->
                carrier.continuing("   ", "outbox publish", headers -> {
                    handed.set(headers);
                    return null;
                }));
        assertThat(handed.get()).isEmpty();
    }

    /**
     * A failed publish must still end its span, and the failure must reach the
     * relay. A span left open shows in the waterfall as an operation still
     * running, which in a system whose job is telling FAILED from UNKNOWN is
     * exactly the wrong story about a send that failed.
     */
    @Test
    void aFailingPublishPropagatesAndClosesItsSpan() {
        Span request = tracer.nextSpan().name("POST /v1/payments").start();
        String stored;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            stored = carrier.capture();
        } finally {
            request.end();
        }

        assertThatThrownBy(() -> carrier.continuing(stored, "outbox publish", headers -> {
            throw new IllegalStateException("broker unreachable");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tracer.currentSpan())
                .as("the scope must be closed even on the failure path")
                .isNull();
    }

    /**
     * Nothing traced means nothing captured, and null is the honest answer. A
     * fabricated context here would produce a trace id that is in no trace,
     * which reads downstream as a broken pipeline rather than as a disabled one.
     */
    @Test
    void capturingOutsideAnySpanReturnsNull() {
        assertThat(carrier.capture()).isNull();
    }

    /**
     * The degraded wiring: no Tracer bean, no Propagator bean. The
     * autoconfiguration falls back to the NOOP pair rather than failing to
     * start, so a service with tracing switched off still publishes.
     */
    @Test
    void theNoopPairPublishesWithoutHeadersAndWithoutFailing() {
        TraceCarrier noop = new TraceCarrier(Tracer.NOOP, Propagator.NOOP);
        AtomicReference<Map<String, String>> handed = new AtomicReference<>();

        assertThat(noop.capture()).isNull();
        assertThatNoCheckedException(() ->
                noop.continuing("00-00000000000000000000000000000001-0000000000000001-01",
                        "outbox publish", headers -> {
                            handed.set(headers);
                            return null;
                        }));
        assertThat(handed.get()).isEmpty();
    }

    // --- helpers ------------------------------------------------------------

    private static String traceIdOf(String traceparent) {
        return traceparent.split("-")[1];
    }

    private static String spanIdOf(String traceparent) {
        return traceparent.split("-")[2];
    }

    /** {@code continuing} declares {@code Exception}; the tests are not interested. */
    private static void assertThatNoCheckedException(ThrowingRunnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("unexpected checked exception", e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
