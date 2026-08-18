package com.payorch.orchestrator.events;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import com.payorch.infra.observability.TraceCarrier;

/**
 * The producer side of phase 6.
 *
 * <p>Every setting here is a durability decision rather than a default, and the
 * three that matter are below. They are set explicitly because the defaults are
 * tuned for throughput on data nobody minds losing, which is the opposite of a
 * payment event.
 */
@Configuration
public class EventsConfiguration {

    @Bean
    public ProducerFactory<String, PaymentEvent> paymentEventProducerFactory(
            @Value("${payorch.events.bootstrap-servers}") String bootstrapServers) {

        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                // JacksonJsonSerializer, not JsonSerializer. The latter is the
                // Jackson 2 implementation and is deprecated for removal in
                // Spring Kafka 4; this project targets Jackson 3 everywhere, as
                // the version catalog records. Taking the deprecated one would
                // have compiled, worked, and quietly pulled a second Jackson
                // into the serialization path.
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class,

                // acks=all. The write is acknowledged only once every in-sync
                // replica has it. With the topic's min.insync.replicas=2 this is
                // what turns RF=3 from "copied three times, eventually" into "a
                // write that cannot land on a single doomed replica" - the two
                // settings are one decision and neither works alone.
                ProducerConfig.ACKS_CONFIG, "all",

                // The idempotent producer. Without it, a retry after a network
                // hiccup can write the same record twice, so the retries that
                // make delivery reliable are also what make it duplicate. With
                // it, the broker deduplicates by producer id and sequence number.
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,

                // Bounded retries rather than infinite: this producer is called
                // from a request thread in the direct arm, and MAX_VALUE would
                // hang a merchant's HTTP call until a broker came back.
                ProducerConfig.RETRIES_CONFIG, 3,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5));
    }

    /**
     * The direct arm's template, with observation ON.
     *
     * <p>One line, and it is the whole trace-propagation story for a synchronous
     * publisher: this template sends from the request thread while the trace is
     * still current, so Spring Kafka's producer observation injects the W3C
     * headers with nothing further to write.
     *
     * <p>It is worth leaving here as the contrast rather than deleting with the
     * rest of the direct arm. Phase 6g's actual work exists BECAUSE the outbox
     * cannot use this: {@link OutboxRelay} publishes on a scheduler thread minutes
     * later, where "the current trace" is the polling loop. The same flag there
     * would produce well-formed headers pointing at the wrong trace, which is
     * strictly worse than no headers at all - a broken trace announces itself,
     * a plausible one does not.
     */
    @Bean
    public KafkaTemplate<String, PaymentEvent> paymentEventKafkaTemplate(
            ProducerFactory<String, PaymentEvent> factory) {
        KafkaTemplate<String, PaymentEvent> template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
        return template;
    }

    /**
     * The naive dual-write arm. Off unless asked for.
     *
     * <p>Kept and selectable rather than deleted once the outbox exists, so the
     * "before" measurement can be reproduced rather than quoted.
     */
    @Bean
    @ConditionalOnProperty(name = "payorch.events.publisher", havingValue = "direct")
    public PaymentEventPublisher directKafkaPublisher(
            KafkaTemplate<String, PaymentEvent> kafka,
            @Value("${payorch.events.topic:payment.events}") String topic) {
        return new DirectKafkaPublisher(kafka, topic);
    }

    /**
     * The outbox arm. The relay publishes STRINGS - the payload is already
     * serialized JSON sitting in a column, and deserializing it only to
     * re-serialize it would risk the relayed bytes differing from the bytes the
     * transaction committed.
     */
    @Bean
    @ConditionalOnProperty(name = "payorch.events.publisher", havingValue = "outbox")
    public ProducerFactory<String, String> outboxProducerFactory(
            @Value("${payorch.events.bootstrap-servers}") String bootstrapServers) {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.RETRIES_CONFIG, 3,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,

                // MAX_BLOCK_MS, and it is not a detail. send() blocks waiting for
                // cluster metadata BEFORE it ever returns a future, and the
                // default is 60 seconds. The relay is a single scheduled thread,
                // so with the brokers down every send stalls the entire relay for
                // a minute - measured as a 247-second drain after a 30-second
                // outage, with zero errors logged, because blocking is not
                // failing. Five seconds makes it fail, log, and retry on the next
                // poll instead of disappearing.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000));
    }

    @Bean
    @ConditionalOnProperty(name = "payorch.events.publisher", havingValue = "outbox")
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            ProducerFactory<String, String> factory) {
        // Observation deliberately NOT enabled - see paymentEventKafkaTemplate.
        // The relay injects the row's stored context by hand instead.
        return new KafkaTemplate<>(factory);
    }

    @Bean
    @ConditionalOnProperty(name = "payorch.events.publisher", havingValue = "outbox")
    public OutboxRelay outboxRelay(OutboxStore store,
                                   KafkaTemplate<String, String> kafka,
                                   @Value("${payorch.events.topic:payment.events}") String topic,
                                   @Value("${payorch.events.outbox.batch-size:100}") int batchSize,
                                   @Value("${payorch.events.outbox.lease-seconds:60}") long leaseSeconds,
                                   ObjectProvider<TraceCarrier> traces) {
        return new OutboxRelay(store, kafka, topic, batchSize,
                java.time.Duration.ofSeconds(leaseSeconds), traces.getIfAvailable());
    }

    /**
     * In the outbox arm the publisher itself does NOTHING, and that is correct:
     * the event was already durably recorded by OutboxWriter inside the payment's
     * transaction, and the relay delivers it. A publisher that also sent here
     * would publish every event twice.
     */
    @Bean
    @ConditionalOnProperty(name = "payorch.events.publisher", havingValue = "outbox")
    public PaymentEventPublisher outboxPublisher() {
        return event -> { };
    }

    /**
     * The default when no publisher is configured: do nothing, loudly enough to
     * be found.
     *
     * <p>A no-op rather than a failure to start, because phases 1 to 5 run
     * without Kafka at all and the async profile is optional. A service that
     * refused to boot without a broker would make every earlier experiment
     * depend on this one.
     */
    @Bean
    @ConditionalOnProperty(name = "payorch.events.publisher", havingValue = "none",
            matchIfMissing = true)
    public PaymentEventPublisher noopPublisher() {
        return event -> { };
    }
}
