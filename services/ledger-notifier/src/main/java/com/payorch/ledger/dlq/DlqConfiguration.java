package com.payorch.ledger.dlq;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import com.payorch.ledger.consume.RetryTopics;

@Configuration
public class DlqConfiguration {

    /**
     * Shares the DLT template with the retry machinery, deliberately.
     *
     * <p>The template that writes records INTO the queue and the one that
     * replays them OUT of it must serialize identically, or a replayed record
     * differs from the one that was dead-lettered - and the difference would
     * only show up in the messages that were already the hardest to handle. One
     * bean makes that impossible rather than merely unlikely.
     */
    @Bean
    public DlqAdmin dlqAdmin(
            @Value("${payorch.ledger.bootstrap-servers}") String bootstrapServers,
            @Value("${payorch.ledger.dlq-topic:" + RetryTopics.DLQ + "}") String dlqTopic,
            @Value("${payorch.ledger.topic:" + RetryTopics.MAIN + "}") String mainTopic,
            KafkaTemplate<String, Object> dltKafkaTemplate) {
        return new DlqAdmin(bootstrapServers, dlqTopic, mainTopic, dltKafkaTemplate);
    }

    @Bean
    public DlqEndpoint dlqEndpoint(DlqAdmin dlqAdmin) {
        return new DlqEndpoint(dlqAdmin);
    }
}
