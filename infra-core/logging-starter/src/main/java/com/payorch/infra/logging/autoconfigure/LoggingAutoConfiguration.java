package com.payorch.infra.logging.autoconfigure;

import com.payorch.infra.logging.jackson.SensitiveJacksonModule;
import com.payorch.infra.logging.sampling.LogSampler;
import com.payorch.infra.logging.sampling.LogSamplingInstaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers {@link SensitiveJacksonModule} on the application's
 * {@code ObjectMapper}.
 *
 * <p>Spring Boot picks up {@code JacksonModule} beans and applies them to the
 * mapper it builds, so declaring the bean is enough - {@code @Sensitive} then
 * holds for HTTP responses and for anything else serialized through Spring's
 * mapper.
 *
 * <p>The log encoder builds its own mapper and never sees this one; that path is
 * covered separately by {@code SensitiveMapperBuilderDecorator} in the logback
 * config.
 */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class LoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SensitiveJacksonModule payorchSensitiveJacksonModule() {
        return new SensitiveJacksonModule();
    }

    /**
     * Trace-based log sampling, <strong>off unless asked for</strong>.
     *
     * <p>The default is 1.0 - keep every line - and that is a decision rather
     * than laziness. Two things in this project read container output as if it
     * were complete, and both are load-bearing:
     *
     * <ul>
     *   <li>the <strong>PAN-leak build test</strong> scans every line of every
     *       container for card data and fails the build on a hit. At 1% sampling
     *       it inspects 1% of the lines, so a leak has a 99% chance of passing.
     *       A control that can be silently defeated by an unrelated setting is
     *       not a control;</li>
     *   <li>every <strong>experiment writeup</strong> from 00 to 07 quotes log
     *       lines as evidence. Turning sampling on by default would not
     *       invalidate those numbers, but it would quietly make them
     *       unreproducible.</li>
     * </ul>
     *
     * <p>So sampling is something an environment opts into when its volume
     * justifies it - measured at 4.00 lines and 2,514 bytes per payment, which
     * is 75 MB/minute at 500 rps - and the PAN scan refuses to report a clean
     * result unless it can prove sampling was off for the run it scanned.
     */
    @Bean
    @ConditionalOnMissingBean
    public LogSampler payorchLogSampler(
            @Value("${payorch.logging.sampling.success-rate:1.0}") double successRate) {
        return new LogSampler(successRate);
    }

    @Bean
    public LogSamplingInstaller payorchLogSamplingInstaller(LogSampler sampler) {
        return new LogSamplingInstaller(sampler);
    }
}
