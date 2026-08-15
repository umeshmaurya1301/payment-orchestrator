package com.payorch.infra.logging;

import java.util.LinkedHashMap;
import java.util.Map;

import net.logstash.logback.argument.StructuredArguments;

/**
 * Builds the structured field set for one log line.
 *
 * <p>Use it instead of building a message string:
 *
 * <pre>{@code
 * log.info("payment authorized",
 *     LogEvent.event()
 *         .with(LogFields.PAYMENT_ID, paymentId)
 *         .with(LogFields.PSP_ID, pspId)
 *         .with(LogFields.LATENCY_MS, elapsed)
 *         .args());
 * }</pre>
 *
 * <p>The message stays a constant, so it can be grouped and counted, and the
 * varying parts land as typed JSON fields that can be filtered on. String
 * concatenation gives up both of those and is the usual way a PAN ends up in a
 * log line.
 *
 * <p>{@link #with} rejects any name outside {@link LogFields#ALLOWED}. That is
 * the allowlist being enforced rather than merely documented - a new field is a
 * deliberate edit to the schema, not something that appears because a DTO grew
 * a property.
 */
public final class LogEvent {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private LogEvent() {
    }

    public static LogEvent event() {
        return new LogEvent();
    }

    /**
     * @throws IllegalArgumentException if {@code name} is not in the schema.
     *         Deliberately fatal: a field that is not in {@link LogFields} has
     *         not been reviewed for whether it can carry cardholder data.
     */
    public LogEvent with(String name, Object value) {
        if (!LogFields.ALLOWED.contains(name)) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not in the log field allowlist. Add it to LogFields "
                            + "only after checking it cannot carry PII or cardholder data.");
        }
        fields.put(name, value);
        return this;
    }

    /** The varargs array to hand to an SLF4J call. */
    public Object[] args() {
        return fields.entrySet().stream()
                .map(e -> StructuredArguments.keyValue(e.getKey(), e.getValue()))
                .toArray();
    }

    /**
     * The varargs array with {@code throwable} appended, for logging an
     * exception alongside the fields.
     *
     * <p>Needed because {@code log.error(msg, event.args(), ex)} does not do
     * what it looks like it does - the array binds as a single vararg element
     * rather than being spread, and the stack trace is silently dropped. SLF4J
     * only treats a throwable specially when it is the last element of the
     * flattened argument array, which is what this produces.
     */
    public Object[] args(Throwable throwable) {
        Object[] base = args();
        Object[] withThrowable = java.util.Arrays.copyOf(base, base.length + 1);
        withThrowable[base.length] = throwable;
        return withThrowable;
    }
}
