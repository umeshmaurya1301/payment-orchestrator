package com.payorch.orchestrator.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutingStrategyTest {

    @Test
    @DisplayName("known names parse, case-insensitively")
    void parsesKnownNames() {
        assertThat(RoutingStrategy.parse("CHEAPEST")).isEqualTo(RoutingStrategy.CHEAPEST);
        assertThat(RoutingStrategy.parse("least_latency")).isEqualTo(RoutingStrategy.LEAST_LATENCY);
        assertThat(RoutingStrategy.parse("  Priority  ")).isEqualTo(RoutingStrategy.PRIORITY);
    }

    @Test
    @DisplayName("an unknown or empty value falls back rather than throwing")
    void unknownFallsBack() {
        // This is read on the payment path, from a column an operator can edit
        // at runtime. Throwing would mean one typo stops every payment for that
        // merchant; routing by a sensible default and complaining loudly is the
        // better failure.
        assertThat(RoutingStrategy.parse("cheepest")).isEqualTo(RoutingStrategy.HEALTH_WEIGHTED);
        assertThat(RoutingStrategy.parse(null)).isEqualTo(RoutingStrategy.HEALTH_WEIGHTED);
        assertThat(RoutingStrategy.parse("")).isEqualTo(RoutingStrategy.HEALTH_WEIGHTED);
    }
}
