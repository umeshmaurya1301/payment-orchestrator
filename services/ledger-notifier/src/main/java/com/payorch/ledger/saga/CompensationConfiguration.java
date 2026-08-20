package com.payorch.ledger.saga;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * The producer that asks for compensations. Phase 6k.
 *
 * <h2>Why not reuse dltProducerFactory</h2>
 *
 * <p>It is right there, it is already configured with {@code acks=all} and
 * idempotence, and reusing it would save a TCP connection. It also uses a
 * {@link org.springframework.kafka.support.serializer.DelegatingByTypeSerializer}
 * keyed by class, so adding compensations to it means the DLQ path&#39;s serializer
 * map has to know about a saga type - and the failure mode when somebody later
 * forgets is a {@code SerializationException} thrown from inside the
 * {@code @DltHandler}, which is the one method in this service that must not
 * throw.
 *
 * <p>A separate factory costs one producer client and keeps the compensation
 * path&#39;s settings stated where the compensation path can be read.
 */
@Configuration
public class CompensationConfiguration {

    @Bean
    public ProducerFactory<String, CompensationRequest> compensationProducerFactory(
            @Value("${payorch.ledger.bootstrap-servers}") String bootstrapServers) {

        Map<String, Object> config = new LinkedHashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // acks=all and idempotence, for the reason the whole phase exists: this
        // message is the only record anywhere that money needs to come back. A
        // compensation lost to a leader election is a capture nobody ever undoes.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, 3);

        // Bounded, because the caller blocks on the send and the caller is a
        // Kafka listener thread. Phase 6b's lesson, third application: blocking
        // is not failing, and an unbounded wait for metadata during a broker
        // outage stalls the partition rather than reporting a problem.
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);

        DefaultKafkaProducerFactory<String, CompensationRequest> factory =
                new DefaultKafkaProducerFactory<>(config);
        factory.setValueSerializer(new JacksonJsonSerializer<>());
        return factory;
    }

    @Bean
    public KafkaTemplate<String, CompensationRequest> compensationKafkaTemplate(
            ProducerFactory<String, CompensationRequest> compensationProducerFactory) {
        return new KafkaTemplate<>(compensationProducerFactory);
    }
}
