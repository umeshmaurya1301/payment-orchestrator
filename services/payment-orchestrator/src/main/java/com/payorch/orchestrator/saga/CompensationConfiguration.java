package com.payorch.orchestrator.saga;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The compensation consumer's wiring. Phase 6k.
 *
 * <h2>BLOCKING retry here, non-blocking in the ledger - and that is not an
 * inconsistency</h2>
 *
 * <p>The ledger uses {@code @RetryableTopic} because a stuck record there holds
 * up a partition carrying every payment that hashed to it. Head-of-line blocking
 * on the main event stream is unacceptable, so failures are published FORWARD to
 * delay topics and the partition advances.
 *
 * <p>None of that reasoning applies to this topic. It carries at most one record
 * per dead-lettered capture - a number that is zero on a healthy day - so there
 * is no line behind the stuck record to block. Meanwhile the two properties the
 * ladder gives up are exactly the two that matter most here:
 *
 * <ul>
 *   <li><strong>Order.</strong> Publishing forward reorders a record against its
 *       own siblings. Harmless for the ledger, whose postings are commutative;
 *       not harmless for a state machine, and a compensation is a state
 *       transition.</li>
 *   <li><strong>Simplicity.</strong> The ladder needs four topics per listener,
 *       created with the right replication factor, plus a scheduler bean. That
 *       is a lot of moving parts to protect a partition that has nothing on
 *       it.</li>
 * </ul>
 *
 * <p>So: three attempts, five seconds apart, then {@code payment.compensation.dlq}
 * and a person. The retry is deliberately SHORT, because a provider that will
 * not reverse a capture is rarely a transient, and the useful outcome is a human
 * seeing it quickly rather than a machine trying for eleven minutes.
 *
 * <h2>Why {@code @EnableKafka} is here</h2>
 *
 * <p>The orchestrator has never consumed anything - it publishes through the
 * outbox relay and that is all - so nothing in this service has ever
 * bootstrapped the {@code @KafkaListener} infrastructure. Boot registers it from
 * its own Kafka autoconfiguration, which this service does not use: Kafka is
 * configured entirely under {@code payorch.*} here, deliberately, so that the
 * publisher can be switched off without leaving half-configured beans behind.
 * Without this annotation the listener is a method nobody calls - inert in
 * exactly the way {@code @RetryableTopic} was in the ledger before
 * {@code @EnableKafkaRetryTopic} was added, which is the fifth time this project
 * has met a component that looks configured and does nothing.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "payorch.saga.compensation.enabled", havingValue = "true")
public class CompensationConfiguration {

    @Bean
    public ConsumerFactory<String, CompensationMessage> compensationConsumerFactory(
            @Value("${payorch.events.bootstrap-servers}") String bootstrapServers,
            @Value("${payorch.saga.compensation.group:orchestrator-compensation}") String groupId) {

        JacksonJsonDeserializer<CompensationMessage> json =
                new JacksonJsonDeserializer<>(CompensationMessage.class);
        // No type headers on the wire - the payload is a plain domain message,
        // so consumers are not coupled to the class names of the producer.
        json.setUseTypeHeaders(false);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // EARLIEST. A compensation published while this consumer was down is a
        // capture still waiting to be undone; `latest` would skip it silently,
        // and the whole point of the topic is that the request survives.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // One at a time. Each record is a provider call that moves money; there
        // is no throughput argument for batching them, and a small max.poll
        // keeps the redelivery set after a rebalance to a single payment.
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);

        // The broker will happily create a topic a consumer subscribes to, with
        // its own defaults - RF=1, one partition - regardless of what Spring is
        // told not to create. Measured in phase 6f, when payment.events.dlq was
        // deleted to clear a bad run and came back unreplicated. This topic is
        // created by tools/kafka/topics.sh with RF=3.
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(config,
                new StringDeserializer(),
                // Wraps the JSON deserializer so a malformed payload is a failed
                // RECORD rather than a consumer that cannot advance past it.
                new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CompensationMessage>
            compensationListenerFactory(ConsumerFactory<String, CompensationMessage> consumerFactory,
                                        DefaultErrorHandler compensationErrorHandler,
                                        @Value("${payorch.saga.compensation.autostart:true}")
                                        boolean autoStart) {

        ConcurrentKafkaListenerContainerFactory<String, CompensationMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // ONE thread, not one per partition. Concurrency here would buy nothing
        // - the topic is nearly always empty - and it would mean two threads
        // reversing captures against the same provider during exactly the
        // incident that produced them.
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Phase 6g. Joins the compensation to the trace of the API call that
        // created the payment, however many minutes earlier that was - which for
        // this listener is the only way to see the whole story, because the
        // interesting part happened in another service before this record
        // existed.
        factory.getContainerProperties().setObservationEnabled(true);

        factory.setCommonErrorHandler(compensationErrorHandler);
        factory.setAutoStartup(autoStart);
        return factory;
    }

    /**
     * Three tries, then out.
     *
     * <p>The default error handler retries nine times in quick succession and
     * then <strong>logs and moves on</strong> - the behaviour that silently
     * skipped 312 events in phase 6e. A recoverer that publishes to a
     * dead-letter topic is what turns "gave up" from a log line into a record
     * somebody can find, and for a message asking to give money back, giving up
     * quietly is not an option.
     */
    @Bean
    public DefaultErrorHandler compensationErrorHandler(
            KafkaTemplate<String, Object> compensationDlqTemplate,
            @Value("${payorch.saga.compensation.dlq-topic:payment.compensation.dlq}")
            String dlqTopic,
            @Value("${payorch.saga.compensation.retry-attempts:3}") long attempts,
            @Value("${payorch.saga.compensation.retry-delay-ms:5000}") long delayMs) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                compensationDlqTemplate,
                // A fixed destination, not the default "<topic>.DLT" suffix.
                // Named explicitly so the topics script and the consumer cannot
                // disagree about it silently. Partition -1 lets the producer
                // choose, rather than assuming the DLQ has as many partitions as
                // the source.
                (record, exception) -> new TopicPartition(dlqTopic, -1));

        // attempts - 1: FixedBackOff counts RETRIES, not total tries. Off by one
        // here means four provider calls where the comment says three.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(delayMs, attempts - 1));
    }

    /**
     * The producer behind the recoverer.
     *
     * <p>Delegating by type for the reason phase 6f found on the ledger side: a
     * record whose LISTENER threw carries a deserialized message and needs
     * Jackson, while a record whose DESERIALIZER threw has no object at all and
     * must be republished as the original {@code byte[]}. Handing those bytes to
     * Jackson base64-encodes them, so the DLQ would hold a quoted blob instead
     * of the message somebody is trying to read - defeating the feature in the
     * exact case it exists for.
     */
    @Bean
    public ProducerFactory<String, Object> compensationDlqProducerFactory(
            @Value("${payorch.events.bootstrap-servers}") String bootstrapServers) {

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        // Phase 6b, applied on a consumer thread: blocking is not failing. The
        // default 60s would stall this listener waiting for metadata during a
        // broker outage.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(config);
        factory.setValueSerializer(new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                CompensationMessage.class, new JacksonJsonSerializer<CompensationMessage>())));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> compensationDlqTemplate(
            ProducerFactory<String, Object> compensationDlqProducerFactory) {
        return new KafkaTemplate<>(compensationDlqProducerFactory);
    }
}
