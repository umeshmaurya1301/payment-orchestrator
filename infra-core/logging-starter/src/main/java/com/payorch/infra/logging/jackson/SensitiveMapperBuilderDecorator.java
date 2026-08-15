package com.payorch.infra.logging.jackson;

import net.logstash.logback.decorate.MapperBuilderDecorator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers {@link SensitiveJacksonModule} on the mapper the log encoder uses.
 *
 * <p>Wired from logback config as a {@code <decorator>} on the encoder. Without
 * this, {@code @Sensitive} would be honoured in HTTP responses but not when the
 * same object is handed to a logger as a structured argument - which is exactly
 * the case we care most about.
 */
public final class SensitiveMapperBuilderDecorator
        implements MapperBuilderDecorator<JsonMapper, JsonMapper.Builder> {

    @Override
    public JsonMapper.Builder decorate(JsonMapper.Builder builder) {
        return builder.addModule(new SensitiveJacksonModule());
    }
}
