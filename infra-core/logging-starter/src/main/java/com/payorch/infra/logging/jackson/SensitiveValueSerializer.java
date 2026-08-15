package com.payorch.infra.logging.jackson;

import com.payorch.infra.logging.MaskStrategy;
import com.payorch.infra.logging.Masking;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a masked string in place of the real value.
 *
 * <p>Jackson 3 renamed {@code JsonSerializer} to {@link ValueSerializer} and
 * {@code SerializerProvider} to {@link SerializationContext}; Spring Boot 4
 * ships Jackson 3 ({@code tools.jackson}) as the primary Jackson, so this is
 * the correct base class rather than the 2.x one.
 */
public final class SensitiveValueSerializer extends ValueSerializer<Object> {

    private final MaskStrategy strategy;

    public SensitiveValueSerializer(MaskStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt)
            throws JacksonException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        // Deliberately routed through String.valueOf rather than only handling
        // CharSequence: a PAN held as a Long must not slip through unmasked
        // because it was the wrong type.
        gen.writeString(Masking.apply(strategy, String.valueOf(value)));
    }
}
