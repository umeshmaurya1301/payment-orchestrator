package com.payorch.ledger.consume;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * The consumer side. Every setting here is a correctness decision.
 *
 * <h2>{@code @EnableKafkaRetryTopic}, and why it is not optional</h2>
 *
 * <p>Boot 4's Kafka autoconfiguration registers {@code @EnableKafka} and a
 * properties-driven {@code RetryTopicConfiguration} bean behind
 * {@code spring.kafka.retry.topic.enabled}. It does <strong>not</strong>
 * bootstrap the support that makes the {@code @RetryableTopic} ANNOTATION do
 * anything. Without this, the annotation on
 * {@link PaymentEventConsumer#onPaymentEvent} is inert - which would be the
 * fifth time this project has met a component that looks configured and does
 * nothing.
 */
@Configuration
@EnableKafkaRetryTopic
public class ConsumerConfiguration {

    @Bean
    public ConsumerFactory<String, PaymentEventMessage> paymentEventConsumerFactory(
            @Value("${payorch.ledger.bootstrap-servers}") String bootstrapServers,
            @Value("${payorch.ledger.group:ledger-notifier}") String groupId) {

        JacksonJsonDeserializer<PaymentEventMessage> json =
                new JacksonJsonDeserializer<>(PaymentEventMessage.class);
        // The producer sends no type headers - the payload is a plain domain
        // event, deliberately, so consumers are not coupled to the producer's
        // class names. Without this the deserializer looks for a __TypeId__
        // header and fails every message.
        json.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG, groupId,

                        // EARLIEST. A new consumer group must see the events that
                        // already exist, not silently skip to the end - the
                        // ledger's job is to account for every payment, and
                        // `latest` would mean a group created after an incident
                        // never learns about the payments during it.
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",

                        // No auto-commit. Offsets are committed by the container
                        // after the listener returns normally, so a message whose
                        // processing throws is redelivered rather than skipped.
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,

                        // Small batches. This is a ledger, not a firehose: the
                        // cost of re-processing after a rebalance is bounded by
                        // how much was in flight.
                        ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50,

                        // The other half of autoCreateTopics=false, and the half
                        // that actually bites. Spring not creating a topic does
                        // nothing to stop the BROKER creating one: a consumer
                        // subscribing to a name that does not exist is enough,
                        // and the broker obliges with its own defaults. Measured
                        // during phase 6f - payment.events.dlq was deleted to
                        // clear a bad run and came back as RF=1, one partition,
                        // recreated by this service's own subscription before
                        // the topics script could run.
                        //
                        // The failure is silent and permanent: the topic looks
                        // present, the consumer is happy, and the DLQ is the one
                        // place in the system with no replication.
                        ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false),
                new StringDeserializer(),
                // ErrorHandlingDeserializer wraps the JSON one so a malformed
                // payload becomes a failed RECORD rather than a poisoned
                // consumer that cannot advance past it.
                new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEventMessage>
            paymentEventListenerFactory(ConsumerFactory<String, PaymentEventMessage> consumerFactory,
                                        @Value("${payorch.ledger.listener-autostart:true}") boolean autoStart) {

        ConcurrentKafkaListenerContainerFactory<String, PaymentEventMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // One consumer thread per partition, up to 3. Ordering is per partition
        // and the key is paymentId, so parallelism across partitions never
        // reorders one payment's events.
        factory.setConcurrency(3);

        // RECORD: commit after each successfully processed record. The
        // alternative, BATCH, commits after the whole poll - so one failure
        // late in a batch redelivers everything before it, and a ledger would
        // rely entirely on idempotency to survive its own normal operation.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Off in the context test, which has no broker. Not a test-only hook
        // bolted on: an operator wants this switch during an incident too, to
        // stop consuming without stopping the service and losing its /actuator.
        factory.setAutoStartup(autoStart);

        return factory;
    }

    /**
     * The producer that forwards failed records down the retry ladder and into
     * the DLQ.
     *
     * <h2>Why the value serializer is a delegating one</h2>
     *
     * <p>Two different kinds of thing get published to these topics and they are
     * not the same type:
     *
     * <ul>
     *   <li>a record whose LISTENER threw carries a deserialized
     *       {@link PaymentEventMessage}, which needs Jackson;</li>
     *   <li>a record whose DESERIALIZER threw has no object at all - Spring's
     *       recoverer republishes the original {@code byte[]}, which must go out
     *       untouched. Handing those bytes to Jackson would serialize them as a
     *       base64 string, so the DLQ would hold a quoted blob instead of the
     *       message somebody is trying to read.</li>
     * </ul>
     *
     * <p>The second case is the whole reason the DLQ exists for poison messages,
     * so getting it wrong would defeat the feature in exactly the situation it
     * was built for.
     *
     * <p>{@code acks=all} and idempotence here as well: a DLQ write that is not
     * durable loses the evidence of the failure, which is worse than losing the
     * message, because nobody knows to look.
     */
    @Bean
    public ProducerFactory<String, Object> dltProducerFactory(
            @Value("${payorch.ledger.bootstrap-servers}") String bootstrapServers) {

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        // Phase 6b's lesson, applied on the consumer side: blocking is not
        // failing. The default 60s would stall the listener thread - and with it
        // the whole partition - waiting for metadata during a broker outage.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(config);
        factory.setValueSerializer(new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                PaymentEventMessage.class, new JacksonJsonSerializer<PaymentEventMessage>())));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate(
            ProducerFactory<String, Object> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    /**
     * The scheduler that resumes a paused retry partition.
     *
     * <p>Required, not optional - without it the context fails to start with
     * <em>"Either a RetryTopicSchedulerWrapper or TaskScheduler bean is
     * required"</em>, which is a much better failure than the alternative and
     * worth understanding rather than silencing.
     *
     * <p>It exists because of HOW the delay is implemented. A tier-2 record must
     * wait a minute, and the consumer cannot simply sleep - that is the blocking
     * retry the whole design rejects. So the container polls the retry topic,
     * sees a record whose backoff timestamp is in the future, <strong>pauses the
     * partition</strong>, seeks back to that offset, and hands a resume task to
     * this scheduler. The wait happens on a timer thread; the listener thread
     * goes back to work.
     *
     * <p>That mechanism is also why the tier topics are cheap. Nothing is held
     * open for ten minutes - the partition is simply not being read, and the
     * broker does not care.
     *
     * <p>A dedicated scheduler rather than Boot's shared one: this project's
     * other {@code @Scheduled} work includes the outbox relay, and a resume task
     * that queued behind a slow poll would silently stretch the tier delays into
     * something other than what the annotation says.
     */
    @Bean
    public RetryTopicSchedulerWrapper retryTopicScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("retry-resume-");
        // Do not let a pending resume block shutdown: the record is durably in
        // its retry topic and will be picked up on the next start. Waiting ten
        // minutes for a tier-3 timer would turn every deploy into an outage.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return new RetryTopicSchedulerWrapper(scheduler);
    }
}
