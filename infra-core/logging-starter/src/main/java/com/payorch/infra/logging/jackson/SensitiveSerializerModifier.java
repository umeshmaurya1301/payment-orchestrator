package com.payorch.infra.logging.jackson;

import java.io.Serial;
import java.util.List;

import com.payorch.infra.logging.Sensitive;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Swaps in {@link SensitiveValueSerializer} for every property carrying
 * {@link Sensitive}.
 *
 * <p>Doing it here rather than with {@code @JsonSerialize(using = ...)} on each
 * field is the whole point: the annotation stays declarative and one line long,
 * and there is no second thing to remember. A field that is marked is masked,
 * everywhere, in every mapper this module is registered on.
 */
public final class SensitiveSerializerModifier extends ValueSerializerModifier {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                     BeanDescription.Supplier beanDescRef,
                                                     List<BeanPropertyWriter> beanProperties) {
        for (BeanPropertyWriter writer : beanProperties) {
            // getAnnotation consults the merged view of field, getter and
            // constructor parameter, so it finds the annotation on a record
            // component as well as on a plain field.
            Sensitive sensitive = writer.getAnnotation(Sensitive.class);
            if (sensitive != null) {
                writer.assignSerializer(new SensitiveValueSerializer(sensitive.value()));
            }
        }
        return beanProperties;
    }
}
