package com.payorch.orchestrator;

import com.payorch.orchestrator.connector.ConnectorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestratorConfiguration {

    @Bean
    public ConnectorClient connectorClient(@Value("${payorch.connector.base-url}") String baseUrl) {
        return new ConnectorClient(baseUrl);
    }
}
