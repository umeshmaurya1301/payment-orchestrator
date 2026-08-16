package com.payorch.infra.logging.sampling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ch.qos.logback.classic.LoggerContext;

/**
 * Attaches {@link SamplingTurboFilter} to the running Logback context, and says
 * loudly what it did.
 *
 * <p>The same shape as {@code LogbackOtelInstaller} and for the same reason:
 * Logback starts long before the Spring context, so anything that depends on a
 * configured bean has to be installed afterwards.
 *
 * <h2>Why it announces itself</h2>
 *
 * <p>Sampling is a control that makes evidence disappear, and its failure mode is
 * that nobody remembers it is on. Two things in this project read container
 * output as if it were complete:
 *
 * <ul>
 *   <li>the <strong>PAN-leak build test</strong>, which scans every line for card
 *       data - at 1% sampling it would scan 1% of the lines and report green;</li>
 *   <li>every <strong>experiment writeup</strong>, which quotes log lines as
 *       evidence of what happened during a run.</li>
 * </ul>
 *
 * <p>So the startup line is not decoration. It is emitted at WARN when sampling
 * is active - deliberately louder than the surrounding INFO - and it is itself
 * unsampled, because it is logged before any trace exists.
 */
public class LogSamplingInstaller implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LogSamplingInstaller.class);

    /**
     * Published into MDC-free startup output and readable by the PAN scan, which
     * refuses to trust a clean result unless it can prove sampling was off.
     */
    public static final String DISABLED_MARKER = "log sampling DISABLED - every line is kept";

    private final LogSampler sampler;

    public LogSamplingInstaller(LogSampler sampler) {
        this.sampler = sampler;
    }

    @Override
    public void afterPropertiesSet() {
        if (!sampler.enabled()) {
            // Still logged, and still the whole sentence. "No output" is not a
            // statement about sampling; the PAN scan needs a positive assertion.
            log.info(DISABLED_MARKER);
            return;
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        SamplingTurboFilter filter = new SamplingTurboFilter(sampler);
        filter.setContext(context);
        filter.start();
        context.addTurboFilter(filter);

        log.warn("log sampling ENABLED at {}% of successful traces - container output is "
                        + "NO LONGER COMPLETE. WARN and above are still kept in full. "
                        + "The PAN-leak test must not be trusted against sampled logs.",
                sampler.successRate() * 100);
    }
}
