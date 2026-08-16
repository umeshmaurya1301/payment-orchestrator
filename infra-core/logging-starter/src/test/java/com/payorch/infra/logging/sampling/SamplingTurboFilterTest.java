package com.payorch.infra.logging.sampling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.payorch.infra.logging.LogFields;

import ch.qos.logback.classic.Level;
import ch.qos.logback.core.spi.FilterReply;

class SamplingTurboFilterTest {

    /** Rate 0.0: everything the sampler is consulted about is dropped. */
    private final SamplingTurboFilter dropAll = new SamplingTurboFilter(new LogSampler(0.0));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private FilterReply decide(SamplingTurboFilter filter, Level level) {
        return filter.decide(null, null, level, "message", null, null);
    }

    @Test
    @DisplayName("WARN and ERROR survive even at rate 0.0 - 100% of errors is not negotiable")
    void errorsAreNeverSampled() {
        MDC.put(LogFields.TRACE_ID, "0123456789abcdef0123456789abcdef");

        assertThat(decide(dropAll, Level.WARN)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide(dropAll, Level.ERROR)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("INFO from a sampled-out trace is denied")
    void ordinaryLinesAreDropped() {
        MDC.put(LogFields.TRACE_ID, "0123456789abcdef0123456789abcdef");

        assertThat(decide(dropAll, Level.INFO)).isEqualTo(FilterReply.DENY);
        assertThat(decide(dropAll, Level.DEBUG)).isEqualTo(FilterReply.DENY);
    }

    @Test
    @DisplayName("a line with no trace id is kept - startup logs its effective config")
    void untracedLinesSurvive() {
        assertThat(decide(dropAll, Level.INFO)).isEqualTo(FilterReply.NEUTRAL);

        MDC.put(LogFields.TRACE_ID, "");
        assertThat(decide(dropAll, Level.INFO)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("keeping returns NEUTRAL, not ACCEPT, so logger levels still apply")
    void keepDoesNotOverrideLevels() {
        SamplingTurboFilter keepAll = new SamplingTurboFilter(LogSampler.keepEverything());
        MDC.put(LogFields.TRACE_ID, "0123456789abcdef0123456789abcdef");

        // ACCEPT here would bypass the logger's own level check and switch DEBUG
        // on across every service - a sampling filter that increases log volume.
        assertThat(decide(keepAll, Level.INFO)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide(keepAll, Level.DEBUG)).isEqualTo(FilterReply.NEUTRAL);
    }
}
