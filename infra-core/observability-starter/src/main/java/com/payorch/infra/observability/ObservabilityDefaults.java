package com.payorch.infra.observability;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Sane observability defaults for every service, in one place.
 *
 * <p>An {@link EnvironmentPostProcessor} rather than five copies of the same
 * YAML block. The alternative was tried first and is worse than it looks: five
 * copies of a sampling probability is five places for them to drift, and the
 * symptom of drift is an experiment whose traces are 1% of what the writeup
 * assumes, on one service, silently.
 *
 * <p><strong>Lowest precedence.</strong> Everything here is added as the last
 * property source, so any service's own {@code application.yml}, any environment
 * variable and any command-line argument still wins. These are defaults, not
 * policy - a service that needs different sampling says so and is obeyed.
 *
 * <h2>The choices</h2>
 *
 * <ul>
 *   <li><strong>100% sampling.</strong> Deliberate, and deliberately temporary.
 *       The phase-4 plan's own trap list says not to turn sampling on before
 *       correlation works, because debugging a trace pipeline with 1% of the
 *       evidence is how a working pipeline gets declared broken. Sampling lands
 *       once a trace can be followed end to end.</li>
 *   <li><strong>Histogram buckets, not published percentiles.</strong> The one
 *       setting in this file that is not a convenience. Percentiles do not
 *       aggregate, so a P99 computed inside each instance cannot be combined
 *       across instances - see {@link RollingLatency}. Buckets can.</li>
 *   <li><strong>W3C propagation.</strong> The default, stated explicitly because
 *       it is the contract between services and a silent change to B3 would
 *       break traces at the hop rather than at startup.</li>
 * </ul>
 */
public class ObservabilityDefaults implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();

        // See the class javadoc: 100% until correlation is demonstrated.
        defaults.put("management.tracing.sampling.probability", "1.0");
        defaults.put("management.tracing.propagation.type", "W3C");

        // Where the collector lives. Overridden per environment; absent in a
        // unit test, which is why the exporter must not be required to start.
        defaults.put("management.otlp.tracing.endpoint",
                "${OTLP_ENDPOINT:http://localhost:4318/v1/traces}");

        // The service name a trace is attributed to. Without it every span in
        // SigNoz says "unknown_service" and a four-service trace becomes one
        // undifferentiated waterfall.
        defaults.put("management.opentelemetry.resource-attributes.service.name",
                "${spring.application.name:payorch}");

        // OTLP *metrics* push, off. The starter brings micrometer-registry-otlp
        // with it, and it defaults to on - so every service began trying to POST
        // metrics to a collector that does not exist yet, and logged a stack
        // trace per attempt per shutdown. Metrics in this system are scraped
        // from /actuator/prometheus, which phase 2's harness already reads;
        // pushing them over OTLP as well would be a second pipeline nobody
        // looks at, failing loudly.
        //
        // Tracing over OTLP stays on. Traces have no scrape endpoint - a trace
        // that is not exported does not exist anywhere.
        defaults.put("management.otlp.metrics.export.enabled", "false");

        // Buckets. The reason is in RollingLatency's javadoc and it is the
        // difference between a fleet P99 that means something and one that does
        // not.
        defaults.put("management.metrics.distribution.percentiles-histogram.http.server.requests", "true");
        defaults.put("management.metrics.distribution.percentiles-histogram.http.client.requests", "true");

        environment.getPropertySources()
                .addLast(new MapPropertySource("payorch-observability-defaults", defaults));
    }

    @Override
    public int getOrder() {
        // After Boot's own config-data processing, so ${...} placeholders above
        // resolve against properties that have actually been loaded.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
