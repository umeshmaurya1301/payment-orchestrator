package com.payorch.orchestrator.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import org.infra.observability.TraceCarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6g, on the relay side.
 *
 * <p>{@link TraceCarrier} is tested separately against a real OpenTelemetry SDK,
 * so what is left to prove here is the wiring, and it is worth proving because
 * the failure mode is silent. A relay that reads the column, opens the span, and
 * then sends with the three-argument {@code send(topic, key, value)} - which is
 * what this class did until this phase - produces a perfectly good span and a
 * record with no headers on it. Every trace looks right on the producing side
 * and the consumer joins nothing.
 *
 * <p>So the assertion is on the {@link ProducerRecord} that actually reaches
 * Kafka, not on the carrier being called.
 */
class OutboxRelayTest {

    private static final String TOPIC = "payment.events";
    private static final String STORED =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String INJECTED =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-b7ad6b7169203331-01";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxStore store = mock(OutboxStore.class);

    private OutboxRelay relay(TraceCarrier traces) {
        return new OutboxRelay(store, kafka, TOPIC, 100, Duration.ofSeconds(60), traces);
    }

    private static OutboxRelay.Claimed claimed(String traceparent) {
        return new OutboxRelay.Claimed(UUID.randomUUID(),
                "0192abcd-0000-7000-8000-000000000001",
                "{\"eventId\":\"x\"}", traceparent);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<ProducerRecord<String, String>> captureSend() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        return ArgumentCaptor.forClass(ProducerRecord.class);
    }

    /**
     * A carrier that behaves like the real one without an SDK: it hands the work
     * the headers for a NEW span in the stored trace, which is what
     * {@code TraceCarrier.continuing} does.
     */
    private static TraceCarrier carrierHanding(String headerValue) {
        TraceCarrier carrier = mock(TraceCarrier.class);
        try {
            when(carrier.continuing(anyString(), anyString(), any()))
                    .thenAnswer(invocation -> {
                        TraceCarrier.Carried<Object> work = invocation.getArgument(2);
                        return work.apply(Map.of(TraceCarrier.TRACEPARENT, headerValue));
                    });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return carrier;
    }

    /** The point of the phase, at the only place it can be observed. */
    @Test
    void theRelayPutsTheStoredTraceContextOnTheRecord() throws Exception {
        when(store.claim(any(), eq(100))).thenReturn(List.of(claimed(STORED)));
        ArgumentCaptor<ProducerRecord<String, String>> sent = captureSend();

        relay(carrierHanding(INJECTED)).relay();

        verify(kafka).send(sent.capture());
        assertThat(header(sent.getValue(), TraceCarrier.TRACEPARENT))
                .as("without this header the consumer starts a trace of its own")
                .isEqualTo(INJECTED);
    }

    /** The key still decides the partition, which is what orders one payment's events. */
    @Test
    void theRecordKeepsThePaymentIdAsItsKeyAndLetsTheKeyChooseThePartition() throws Exception {
        OutboxRelay.Claimed event = claimed(STORED);
        when(store.claim(any(), eq(100))).thenReturn(List.of(event));
        ArgumentCaptor<ProducerRecord<String, String>> sent = captureSend();

        relay(carrierHanding(INJECTED)).relay();

        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().key()).isEqualTo(event.key());
        assertThat(sent.getValue().partition())
                .as("an explicit partition would break per-payment ordering by key")
                .isNull();
        assertThat(sent.getValue().topic()).isEqualTo(TOPIC);
        assertThat(sent.getValue().value()).isEqualTo(event.payload());
    }

    /**
     * Rows written before V11, and every row written while tracing is off. The
     * event still has to be published - an outbox that declined to relay
     * untraced events would lose money to fix a dashboard.
     */
    @Test
    void anEventWithNoStoredContextIsStillPublished() throws Exception {
        OutboxRelay.Claimed event = claimed(null);
        when(store.claim(any(), eq(100))).thenReturn(List.of(event));
        ArgumentCaptor<ProducerRecord<String, String>> sent = captureSend();

        TraceCarrier carrier = mock(TraceCarrier.class);
        when(carrier.continuing(any(), anyString(), any())).thenAnswer(invocation -> {
            TraceCarrier.Carried<Object> work = invocation.getArgument(2);
            return work.apply(Map.of());
        });

        relay(carrier).relay();

        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().headers().toArray()).isEmpty();
        verify(store).markPublished(event.id());
    }

    /** A service with tracing off has no carrier bean at all. */
    @Test
    void aRelayWithNoTraceCarrierPublishesExactlyAsItDidBefore() {
        OutboxRelay.Claimed event = claimed(STORED);
        when(store.claim(any(), eq(100))).thenReturn(List.of(event));
        ArgumentCaptor<ProducerRecord<String, String>> sent = captureSend();

        relay(null).relay();

        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().headers().toArray()).isEmpty();
        assertThat(sent.getValue().value()).isEqualTo(event.payload());
    }

    /**
     * The behaviour V10 was written for, re-asserted now that a span wraps the
     * send: a failed publish must mark the row and stop the batch, not swallow
     * the failure inside the tracing code.
     */
    @Test
    void aFailedPublishStillMarksTheRowAndStopsTheBatch() {
        OutboxRelay.Claimed first = claimed(STORED);
        OutboxRelay.Claimed second = claimed(STORED);
        when(store.claim(any(), eq(100))).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unreachable")));

        OutboxRelay relay = relay(carrierHanding(INJECTED));
        relay.relay();

        verify(store).markFailed(eq(first.id()), any());
        verify(store, never()).markPublished(any());
        assertThat(relay.relayed()).isZero();
        assertThat(relay.failures()).isEqualTo(1);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
