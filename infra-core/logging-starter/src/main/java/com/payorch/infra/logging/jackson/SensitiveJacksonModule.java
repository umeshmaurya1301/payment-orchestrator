package com.payorch.infra.logging.jackson;

import java.io.Serial;

import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;

/**
 * Registers {@link SensitiveSerializerModifier} on a mapper.
 *
 * <p>Registered in two places, on purpose:
 * <ul>
 *   <li>on Spring's {@code ObjectMapper}, via {@code LoggingAutoConfiguration},
 *       so API responses and event payloads are masked;</li>
 *   <li>on the log encoder's own mapper, via
 *       {@link SensitiveMapperBuilderDecorator}, so objects passed as structured
 *       log arguments are masked too - the encoder does not use Spring's
 *       mapper, so registering in only one place leaves a hole.</li>
 * </ul>
 */
public final class SensitiveJacksonModule extends SimpleModule {

    @Serial
    private static final long serialVersionUID = 1L;

    public SensitiveJacksonModule() {
        super("payorch-sensitive-masking");
    }

    @Override
    public void setupModule(JacksonModule.SetupContext context) {
        super.setupModule(context);
        context.addSerializerModifier(new SensitiveSerializerModifier());
    }
}
