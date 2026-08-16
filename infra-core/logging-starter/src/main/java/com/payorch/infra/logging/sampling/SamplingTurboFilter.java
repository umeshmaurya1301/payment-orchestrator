package com.payorch.infra.logging.sampling;

import org.slf4j.MDC;
import org.slf4j.Marker;

import com.payorch.infra.logging.LogFields;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Drops the ordinary log lines of unsampled traces, as early as it is possible
 * to drop them.
 *
 * <p><strong>A {@code TurboFilter}, not an appender filter.</strong> The
 * distinction is the entire performance argument. An appender-level filter runs
 * after the event has been constructed - message formatted, arguments boxed, MDC
 * copied, caller data possibly captured - so it saves the I/O and none of the
 * work. A {@code TurboFilter} runs <em>before</em> the {@code LoggingEvent}
 * exists, so a denied line costs a map lookup and an integer comparison.
 *
 * <p>Which matters here more than usual, because the thing being protected is a
 * system that fell over at 500 rps in phase 2. Sampling that still allocates per
 * request would be spending the budget it is meant to save.
 *
 * <h2>Order of the checks</h2>
 *
 * <ol>
 *   <li>WARN and above: always kept, without consulting the sampler at all.
 *       100% of errors is the phase-4 requirement and it is not negotiable
 *       against a rate.</li>
 *   <li>No trace id: kept. Startup and scheduled work - see {@link LogSampler}.</li>
 *   <li>Otherwise the sampler decides, from the trace id alone, so every service
 *       agrees without being told.</li>
 * </ol>
 *
 * <p>{@link FilterReply#NEUTRAL} rather than {@link FilterReply#ACCEPT} for a
 * keep: ACCEPT would bypass the logger's own level check and turn DEBUG lines on
 * everywhere. NEUTRAL means "I have no opinion", which is the correct thing for a
 * filter whose only job is to remove.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It is <strong>head-based</strong>. The decision is made when the first line
 * of a trace is emitted, so a request that turns out slow or failed has already
 * lost its earlier INFO lines - only the WARN/ERROR lines themselves survive,
 * via rule 1. Keeping the full history of every failed request means buffering
 * every trace until it completes and flushing on outcome, which is tail-based
 * sampling: unbounded memory in exactly the incident where memory is already the
 * problem. The phase plan calls that "the harder version" and it is deferred
 * knowingly, not overlooked.
 */
public class SamplingTurboFilter extends TurboFilter {

    private final LogSampler sampler;

    public SamplingTurboFilter(LogSampler sampler) {
        this.sampler = sampler;
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (level == null || level.isGreaterOrEqual(Level.WARN)) {
            return FilterReply.NEUTRAL;
        }
        String traceId = MDC.get(LogFields.TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            return FilterReply.NEUTRAL;
        }
        return sampler.keep(traceId) ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
