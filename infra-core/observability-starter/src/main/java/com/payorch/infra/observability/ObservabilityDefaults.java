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
        //
        // NOTE THE PROPERTY NAMES. Boot 4 moved these:
        //
        //   Boot 3   management.otlp.tracing.endpoint
        //   Boot 4   management.opentelemetry.tracing.export.otlp.endpoint
        //
        // The Boot 3 name still appears in the metadata as a deprecated alias,
        // which is worse than if it had been deleted: setting it produces no
        // warning, no error, and no traces. The whole pipeline came up healthy -
        // collector reachable, no export failures logged, services happily
        // running - and ClickHouse held zero spans, because nothing was ever
        // asked to export anywhere.
        String endpoint = "${OTLP_ENDPOINT:http://localhost:4318}";
        defaults.put("management.opentelemetry.tracing.export.otlp.endpoint", endpoint + "/v1/traces");

        // Logs over OTLP as well, which Boot 4 can do natively and Boot 3 could
        // not. This is what makes a log line and its trace one click apart
        // inside SigNoz rather than two systems correlated by eye - the phase
        // plan calls it the highest-value item.
        //
        // This property is necessary and NOT sufficient. It builds the exporter;
        // it does not feed it. Boot 4 ships no Logback bridge, so with only this
        // line set the pipeline comes up clean and exports nothing. See
        // LogbackOtelInstaller for the other half, and the catalog note on
        // opentelemetry-logback-appender for what it costs.
        //
        // The JSON console output stays exactly as it is. Container logs are
        // what the PAN-leak test scans and what `docker compose logs` shows
        // during an experiment; replacing them with an export nobody can read
        // without a browser would be a downgrade for every workflow this
        // project already has.
        defaults.put("management.opentelemetry.logging.export.otlp.endpoint", endpoint + "/v1/logs");

        // The service name a trace is attributed to. Without it every span in
        // SigNoz says "unknown_service" and a four-service trace becomes one
        // undifferentiated waterfall.
        defaults.put("management.opentelemetry.resource-attributes.service.name",
                "${spring.application.name:payorch}");

        // OTLP *metrics* push, off BY DEFAULT and turned on by the SigNoz
        // override. The starter brings micrometer-registry-otlp with it, and it
        // defaults to on - so every service began trying to POST metrics to a
        // collector that did not exist yet, and logged a stack trace per attempt
        // per shutdown.
        //
        // Off is right for the default stack: phases 1-3 reproduce without
        // SigNoz, and the harness reads /actuator/prometheus directly. It is
        // wrong once SigNoz is attached, because the phase-4 dashboards are
        // breaker state and bulkhead saturation - gauges, not spans. No amount
        // of trace data answers "is the breaker open", so metrics have to
        // arrive as metrics. docker/signoz/payorch-obs.override.yml flips it.
        //
        // The meters are the same objects either way. Micrometer publishes one
        // set of instruments to every registry attached to it, so this is a
        // second READER of one source, not a second pipeline that can disagree
        // with the first.
        //
        // NOTE THE NAMESPACE. This one did NOT move in Boot 4: metrics still
        // live under management.otlp.metrics.export, because Boot 4's native
        // OpenTelemetry support covers tracing and logging and stops there.
        // There is no management.opentelemetry.metrics.* to match the two
        // properties above, and looking for one costs an afternoon.
        defaults.put("management.otlp.metrics.export.enabled", "false");

        // Set regardless, so turning the flag on is the only thing the override
        // has to do. Boot 4 dropped the localhost default this had in Boot 3,
        // so without a url an enabled registry exports to nowhere - the same
        // silent-success shape as the tracing endpoint rename.
        defaults.put("management.otlp.metrics.export.url", endpoint + "/v1/metrics");

        // 1m is the default and it is too coarse to watch a chaos run with. A
        // breaker opens and recloses inside 60s; at a 1m step that whole event
        // can land between two samples and the dashboard shows a flat line
        // through the incident.
        defaults.put("management.otlp.metrics.export.step", "15s");

        // DELTA, not the cumulative default. SigNoz's query model expects delta
        // for counters, and feeding it cumulative does not error - it produces
        // rate graphs that are wrong in a plausible-looking way, which is the
        // worst failure mode available. Gauges are unaffected by this setting.
        defaults.put("management.otlp.metrics.export.aggregation-temporality", "delta");

        // Tracing over OTLP stays on. Traces have no scrape endpoint - a trace
        // that is not exported does not exist anywhere.

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
