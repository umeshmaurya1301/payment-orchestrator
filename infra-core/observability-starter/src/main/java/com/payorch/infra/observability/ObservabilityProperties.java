package com.payorch.infra.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for phase 4, under {@code payorch.observability}.
 *
 * @param rollingWindowSeconds how much recent history the per-provider
 *                             percentile covers. 60 s is a compromise with a
 *                             direction: short enough that phase 5 reacts to a
 *                             provider degrading within a minute, long enough
 *                             that a slow provider at low traffic still has
 *                             enough samples for a P99 to mean anything. At
 *                             10 rps a 60 s window holds 600 calls, so the 99th
 *                             is the 6th-slowest - defensible. At 10 s it would
 *                             be the slowest single call, which is a maximum
 *                             wearing a percentile's name.
 */
@ConfigurationProperties(prefix = "payorch.observability")
public record ObservabilityProperties(Integer rollingWindowSeconds) {

    public ObservabilityProperties {
        // Boxed and defaulted, for the reason 3a's properties record explains:
        // constructor binding gives an absent primitive its zero value, and a
        // zero-second window is a percentile over nothing.
        if (rollingWindowSeconds == null || rollingWindowSeconds <= 0) {
            rollingWindowSeconds = 60;
        }
    }
}
