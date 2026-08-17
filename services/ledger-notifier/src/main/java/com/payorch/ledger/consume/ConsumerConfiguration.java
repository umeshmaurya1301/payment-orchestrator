package com.payorch.ledger.consume;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

/**
 * The consumer side. Every setting here is a correctness decision.
 */
@Configuration
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
                        ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50),
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
}
