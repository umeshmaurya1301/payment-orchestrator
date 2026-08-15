package com.payorch.edge;

import com.payorch.edge.merchant.ApiKeyAuthFilter;
import com.payorch.infra.resilience.deadline.DeadlineExecutor;
import com.payorch.infra.resilience.deadline.DeadlinePropagation;
import com.payorch.edge.merchant.MerchantRepository;
import com.payorch.edge.orchestrator.OrchestratorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EdgeConfiguration {

    @Bean
    public OrchestratorClient orchestratorClient(@Value("${payorch.orchestrator.base-url}") String baseUrl,
                                                 DeadlinePropagation propagation,
                                                 DeadlineExecutor deadlines) {
        return new OrchestratorClient(baseUrl, propagation, deadlines);
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(MerchantRepository merchants) {
        return new ApiKeyAuthFilter(merchants);
    }
}
