package com.payorch.infra.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field whose value must never be serialized in the clear.
 *
 * <p>Any annotated property is rewritten by
 * {@code SensitiveSerializerModifier} at serialization time, so it is masked
 * wherever it goes through Jackson - log output, API responses, event payloads -
 * without the call site having to remember.
 *
 * <p>This is the second line of defence, not the first. The primary control is
 * tokenization at the edge (phase 1): a raw PAN should not exist downstream to
 * be annotated in the first place. This annotation catches what tokenization
 * misses, and protects the data that cannot be tokenized (email, mobile, VPA).
 *
 * <p>{@code RECORD_COMPONENT} and {@code PARAMETER} are included so the
 * annotation propagates correctly when placed on a record component, which is
 * how most of our DTOs are declared.
 *
 * <pre>{@code
 * record CardDetails(
 *     @Sensitive(MaskStrategy.PAN) String pan,
 *     @Sensitive String cvv,
 *     String bin,
 *     String last4) { }
 * }</pre>
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Sensitive {

    /** How to render the masked value. Defaults to full redaction. */
    MaskStrategy value() default MaskStrategy.FULL;
}
