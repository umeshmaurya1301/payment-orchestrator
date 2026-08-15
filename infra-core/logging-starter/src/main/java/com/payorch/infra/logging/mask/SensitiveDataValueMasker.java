package com.payorch.infra.logging.mask;

import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * Applies {@link Redactor} to every string value written into the JSON log
 * output.
 *
 * <p>Wired onto the encoder via {@code MaskingJsonGeneratorDecorator}, which
 * means it sees values from every source - the message, MDC entries, structured
 * arguments, stack traces, and fields added by libraries we do not control.
 * That total coverage is what makes it a usable last line of defence.
 *
 * <p>Returning {@code null} is the interface's way of saying "leave this value
 * alone"; it does not write a null. So the common case - a value with nothing
 * sensitive in it - costs one regex scan and no allocation.
 */
public final class SensitiveDataValueMasker implements ValueMasker {

    @Override
    public Object mask(TokenStreamContext context, Object value) {
        if (!(value instanceof CharSequence chars)) {
            return null;
        }
        String original = chars.toString();
        if (original.isEmpty()) {
            return null;
        }
        String redacted = Redactor.redact(original);
        // Redactor returns the same instance when nothing matched.
        return redacted.equals(original) ? null : redacted;
    }
}
