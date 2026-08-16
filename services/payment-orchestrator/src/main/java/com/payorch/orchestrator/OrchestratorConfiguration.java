package com.payorch.orchestrator;

import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.orchestrator.connector.ConnectorClient;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestratorConfiguration {

    @Bean
    public ConnectorClient connectorClient(@Value("${payorch.connector.base-url}") String baseUrl,
                                           DeadlinePropagation propagation,
                                           DeadlineExecutor deadlines,
                                                 ObservationRegistry observations) {
        return new ConnectorClient(baseUrl, propagation, deadlines, observations);
    }

    /**
     * The one metric in this system that is about payments rather than about
     * machinery. See {@link PaymentOutcomeMetrics} for why it took until phase 4
     * to notice it was missing.
     */
    @Bean
    public PaymentOutcomeMetrics paymentOutcomeMetrics(MeterRegistry registry) {
        return new PaymentOutcomeMetrics(registry);
    }
}
