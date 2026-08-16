package com.payorch.infra.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Hands the OpenTelemetry SDK to the Logback appender.
 *
 * <p>Boot 4 builds an {@code SdkLoggerProvider} and an OTLP log exporter, and
 * {@code logback-payorch.xml} declares the appender that should feed them.
 * Nothing connects the two. Logback starts long before the Spring context
 * exists, so the appender comes up with no SDK and quietly drops every event it
 * is handed - which is exactly what it looked like: the appender attached, no
 * errors anywhere, and zero log lines in ClickHouse.
 *
 * <p>One call fixes it, and it has to happen after the context has an
 * {@link OpenTelemetry} bean, which is what this bean is for.
 *
 * <p>Events logged <em>before</em> this runs are buffered by the appender and
 * flushed on install, so the startup lines are not lost - which matters, since
 * startup is where this project logs its effective configuration.
 */
public class LogbackOtelInstaller implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LogbackOtelInstaller.class);

    private final OpenTelemetry openTelemetry;

    public LogbackOtelInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
        log.info("logback -> OpenTelemetry appender installed; log lines now carry their trace");
    }
}
