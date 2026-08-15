package com.payorch.infra.logging.autoconfigure;

import com.payorch.infra.logging.jackson.SensitiveJacksonModule;
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
}
