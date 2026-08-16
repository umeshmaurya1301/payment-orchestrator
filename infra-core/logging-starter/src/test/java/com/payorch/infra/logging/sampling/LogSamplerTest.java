package com.payorch.infra.logging.sampling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogSamplerTest {

    /** A W3C trace id: 32 lowercase hex characters. */
    private static List<String> traceIds(int n) {
        RandomGenerator random = RandomGenerator.getDefault();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add("%016x%016x".formatted(random.nextLong(), random.nextLong()));
        }
        return ids;
    }

    private static long kept(LogSampler sampler, List<String> ids) {
        return ids.stream().filter(sampler::keep).count();
    }

    @Test
    @DisplayName("1% keeps roughly 1% of traces")
    void approximatesTheConfiguredRate() {
        List<String> ids = traceIds(200_000);
        long kept = kept(new LogSampler(0.01), ids);

        // Generous bounds on purpose. This asserts the rate is honoured to an
        // order of magnitude, not that a random sample landed on a number - a
        // tight bound here would be a flaky test dressed up as a precise one.
        assertThat(kept).isBetween(1_600L, 2_400L);
    }

    @Test
    @DisplayName("the same trace id always gets the same verdict, so services agree without coordinating")
    void isDeterministic() {
        LogSampler a = new LogSampler(0.05);
        LogSampler b = new LogSampler(0.05);

        // This is the property the whole design rests on: four services, four
        // separate JVMs, one answer per trace - and therefore complete traces
        // rather than 5% of the lines of every trace.
        for (String id : traceIds(5_000)) {
            assertThat(a.keep(id))
                    .as("trace %s", id)
                    .isEqualTo(b.keep(id));
        }
    }

    @Test
    @DisplayName("rate 1.0 keeps everything and reports itself disabled")
    void keepEverythingIsTheDefault() {
        LogSampler sampler = LogSampler.keepEverything();

        assertThat(sampler.enabled()).isFalse();
        assertThat(kept(sampler, traceIds(1_000))).isEqualTo(1_000);
    }

    @Test
    @DisplayName("rate 0.0 drops every traced line")
    void zeroDropsEverything() {
        assertThat(kept(new LogSampler(0.0), traceIds(1_000))).isZero();
    }

    @Test
    @DisplayName("a malformed or absent trace id is kept, never dropped")
    void malformedTraceIdsSurvive() {
        LogSampler sampler = new LogSampler(0.0);

        // Even at rate 0.0. Something upstream is wrong, and that is not the
        // moment to start discarding evidence.
        assertThat(sampler.keep(null)).isTrue();
        assertThat(sampler.keep("")).isTrue();
        assertThat(sampler.keep("short")).isTrue();
        assertThat(sampler.keep("not-hex-not-hex-not-hex-not-hexx")).isTrue();
    }

    @Test
    @DisplayName("a rate outside 0..1 fails at construction, not at the first log line")
    void rejectsImpossibleRates() {
        assertThatThrownBy(() -> new LogSampler(1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.0 and 1.0");
        assertThatThrownBy(() -> new LogSampler(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
