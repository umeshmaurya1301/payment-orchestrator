package com.payorch.connector;

import com.payorch.connector.provider.MockPspAdapter;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.connector.provider.PspProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns configured providers into adapter beans.
 *
 * <p>One provider in phase 1. When phase 5 adds more, each becomes another
 * {@code @Bean} here and {@code PspAdapterRegistry} picks it up with no further
 * change.
 */
@Configuration
@EnableConfigurationProperties(PspProperties.class)
public class ConnectorConfiguration {

    @Bean
    public MockPspAdapter mockPspAdapter(PspProperties properties,
                                         DeadlinePropagation propagation,
                                         DeadlineExecutor deadlines) {
        return new MockPspAdapter(
                properties.require(MockPspAdapter.PSP_ID).baseUrl(), propagation, deadlines);
    }
}
