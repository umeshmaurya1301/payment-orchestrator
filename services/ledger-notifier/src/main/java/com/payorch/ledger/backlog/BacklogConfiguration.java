package com.payorch.ledger.backlog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.payorch.ledger.consume.RetryTopics;
import com.payorch.ledger.dlq.DlqAdmin;

/**
 * Phase 6i. Lag and DLQ backlog, published as metrics.
 *
 * <p>{@code @EnableScheduling} lives here rather than on the application class
 * because this is the only scheduled work in the service, and putting it beside
 * the thing it schedules means a future reader finds it without grepping.
 */
@Configuration
@EnableScheduling
public class BacklogConfiguration {

    @Bean(destroyMethod = "close")
    public KafkaBacklog kafkaBacklog(
            @Value("${payorch.ledger.bootstrap-servers}") String bootstrapServers) {
        return new KafkaBacklog(bootstrapServers);
    }

    /**
     * The DLQ's backlog is measured against {@link DlqAdmin#REPLAY_GROUP}, not
     * against the ledger's own group, and that is not a detail.
     *
     * <p>Nothing consumes the DLQ in the normal course of events - it exists to
     * be read by a human. The only thing that ever commits an offset on it is
     * {@code /actuator/dlq} replaying records, so the replay group's position IS
     * the definition of "handled". Measuring against the ledger group instead
     * would report every dead-lettered record as permanently pending, and the
     * alert would be on forever from the first incident: the same trap as
     * alerting on record count, arrived at from a different direction.
     */
    @Bean
    public BacklogMetrics backlogMetrics(
            KafkaBacklog kafkaBacklog,
            @Value("${payorch.ledger.topic:" + RetryTopics.MAIN + "}") String mainTopic,
            @Value("${payorch.ledger.group:ledger-notifier}") String group,
            @Value("${payorch.ledger.dlq-topic:" + RetryTopics.DLQ + "}") String dlqTopic) {
        return new BacklogMetrics(kafkaBacklog, mainTopic, group,
                dlqTopic, DlqAdmin.REPLAY_GROUP);
    }
}
