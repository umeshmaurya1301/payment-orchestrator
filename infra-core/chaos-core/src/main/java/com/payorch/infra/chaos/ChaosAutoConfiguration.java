package com.payorch.infra.chaos;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Contributes the seam registry and, where actuator is present, its endpoint.
 *
 * <p>Unconditional on purpose. The registry is an empty map until something
 * arms it, and a disarmed seam costs one hash lookup - so there is no
 * meaningful production cost to leaving it wired, and gating it behind a
 * property would mean the one time it is needed is the one time it is not
 * enabled.
 *
 * <p>The same applies to {@link BeanAssaultAspect}: installed always, injecting
 * nothing until {@code /actuator/chaosbeans} says otherwise. Both control
 * surfaces are exposed only if a service lists them in
 * {@code management.endpoints.web.exposure.include}, so nothing here becomes
 * reachable by accident.
 */
@AutoConfiguration
public class ChaosAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChaosSeams chaosSeams() {
        return new ChaosSeams();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Endpoint.class)
    public ChaosSeamsEndpoint chaosSeamsEndpoint(ChaosSeams seams) {
        return new ChaosSeamsEndpoint(seams);
    }

    @Bean
    @ConditionalOnMissingBean
    public BeanAssaultAspect beanAssaultAspect() {
        return new BeanAssaultAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Endpoint.class)
    public BeanAssaultEndpoint beanAssaultEndpoint(BeanAssaultAspect aspect) {
        return new BeanAssaultEndpoint(aspect);
    }
}
