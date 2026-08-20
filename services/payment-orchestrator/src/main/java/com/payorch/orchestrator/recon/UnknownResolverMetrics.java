package com.payorch.orchestrator.recon;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.payorch.orchestrator.PaymentPersistence;
import com.payorch.orchestrator.domain.PaymentState;

/**
 * The UNKNOWN backlog, as series somebody can alert on. Phase 8a.
 *
 * <h2>Age, not count, is the signal</h2>
 *
 * <p>Phase 8 asks for an alert on "UNKNOWN count and age", and the second half
 * is the one that works. A steady hundred UNKNOWN payments that each resolve
 * within a minute is a busy system behaving correctly; three that have been
 * sitting for an hour is a provider that has stopped answering and a growing
 * pile of money nobody can account for.
 *
 * <p>The count cannot separate those. It is published because the RATE of change
 * is informative and because a threshold is easy to reason about, but the
 * page-worthy condition is the age.
 *
 * <p>Phase 4e's finding applies here in a way worth spelling out: an alert on
 * HTTP 5xx stayed flat while 99.7% of payments failed, because a decline is a
 * 201. The equivalent mistake in this phase would be alerting on "the poller
 * threw an exception" - which stays at zero while the poller cheerfully asks a
 * dead provider about the same payment every hour for a week.
 */
@Component
@ConditionalOnProperty(name = "payorch.recon.unknown-poller.enabled", havingValue = "true")
public class UnknownResolverMetrics implements MeterBinder {

    private final UnknownResolver resolver;
    private final PaymentPersistence persistence;

    public UnknownResolverMetrics(UnknownResolver resolver, PaymentPersistence persistence) {
        this.resolver = resolver;
        this.persistence = persistence;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // --- the backlog, as gauges ---------------------------------------
        //
        // Gauges here and FunctionCounters below, and the distinction is phase
        // 4e's lesson rather than a style choice: a cumulative value declared as
        // a gauge is summed rather than differenced by anything dispatching on
        // declared type, which produced an `increase` of 36 over a window where
        // the underlying counter never moved off 4. A backlog genuinely goes
        // down as well as up, so it genuinely is a gauge.
        Gauge.builder("payorch.payments.unknown", persistence,
                        p -> p.countInState(PaymentState.UNKNOWN))
                .description("Payments whose outcome nobody knows yet")
                .register(registry);

        // THE ONE TO PAGE ON. Seconds, so a threshold reads as a duration.
        Gauge.builder("payorch.payments.unknown.oldest_age_seconds", persistence,
                        p -> p.oldestAgeInState(PaymentState.UNKNOWN).toSeconds())
                .description("Age of the oldest unresolved payment - the number to alert on")
                .register(registry);

        // Not zero-valued in a healthy system either, but every one of these
        // needs a person. A threshold of "more than zero for more than an hour"
        // is a reasonable page.
        Gauge.builder("payorch.payments.unresolved", persistence,
                        p -> p.countInState(PaymentState.UNRESOLVED))
                .description("Payments the poller gave up on - each needs a human")
                .register(registry);

        // --- what the poller did ------------------------------------------

        counter(registry, "payorch.recon.polled",
                "UNKNOWN payments the poller asked a provider about",
                UnknownResolver::polledCount);

        // The most interesting series in this class. Each one is a charge that
        // existed in the world and not in this system until the poller asked -
        // so a step change here is not "the poller is working harder", it is
        // "we were losing authorizations".
        counter(registry, "payorch.recon.resolved_authorized",
                "UNKNOWN payments the provider had authorized all along",
                UnknownResolver::resolvedAuthorizedCount);

        counter(registry, "payorch.recon.resolved_failed",
                "UNKNOWN payments resolved to FAILED",
                UnknownResolver::resolvedFailedCount);

        counter(registry, "payorch.recon.gave_up",
                "UNKNOWN payments the poller stopped asking about",
                UnknownResolver::gaveUpCount);

        // NOT expected to be zero. A provider being slow is ordinary, and this
        // counting up while the backlog holds steady is the system correctly
        // refusing to guess.
        counter(registry, "payorch.recon.inconclusive",
                "Lookups where a provider stayed silent, so nothing was concluded",
                UnknownResolver::inconclusiveCount);

        counter(registry, "payorch.recon.lookup_failed",
                "Lookups that never reached the connector",
                UnknownResolver::lookupFailureCount);
    }

    private void counter(MeterRegistry registry, String name, String description,
                         java.util.function.ToDoubleFunction<UnknownResolver> read) {
        FunctionCounter.builder(name, resolver, read)
                .description(description)
                .register(registry);
    }
}
