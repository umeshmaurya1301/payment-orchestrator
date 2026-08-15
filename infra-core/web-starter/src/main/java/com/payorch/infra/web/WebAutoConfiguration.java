package com.payorch.infra.web;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Wires the shared HTTP concerns into any service that has a web stack.
 *
 * <p>Declared as beans here rather than left to component scanning, so a
 * service picks them up by depending on the starter and does nothing else.
 * Forgetting to scan a package is exactly the kind of per-service divergence
 * this module exists to prevent.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
public class WebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailHandler problemDetailHandler() {
        return new ProblemDetailHandler();
    }
}
