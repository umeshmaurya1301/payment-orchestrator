package com.payorch.edge;

import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.edge.merchant.MerchantRepository;
import com.payorch.edge.orchestrator.OrchestratorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EdgeConfiguration {

    @Bean
    public OrchestratorClient orchestratorClient(@Value("${payorch.orchestrator.base-url}") String baseUrl) {
        return new OrchestratorClient(baseUrl);
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(MerchantRepository merchants) {
        return new ApiKeyAuthFilter(merchants);
    }
}
