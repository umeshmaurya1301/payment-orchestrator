package com.payorch.connector.health;

import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.payorch.connector.config.ProviderConfig;
import com.payorch.connector.config.ProviderConfigStore;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.PspAdapterRegistry;
import org.infra.resilience.breaker.CircuitBreakers;
import org.infra.tokenization.DetokenizedCard;

/**
 * Tests a recovering provider with money nobody spent. Phase 5, the standing
 * question experiment 09 left open.
 *
 * <h2>The 4 points this exists to remove</h2>
 *
 * <p>Experiment 09 measured a 91.5-point improvement from health-weighted
 * routing and a 6.3-point residue against "no spike". Of that residue, ~4
 * points were the provider under test itself — not the failover destination
 * being contractually worse, but <em>psp-a</em>, the very provider recovering.
 * The reason: {@code ProviderHealth} let a half-open breaker through at a
 * capped share of traffic, because until this class existed, routing was the
 * <strong>only</strong> way the breaker's half-open probes ever got made. A
 * provider starved of all traffic can never prove it is back, so some real
 * customer traffic had to be sacrificed to test it.
 *
 * <p>This class replaces that sacrifice. It watches for a breaker in
 * {@code HALF_OPEN} and calls the provider itself — through the exact same
 * {@link CircuitBreakers} instance a real payment would use, so the call
 * genuinely counts toward the breaker's decision to close or reopen — using a
 * card and amount that were never a customer's. With this running,
 * {@link org.infra.observability.ProviderHealth} gates half-open
 * traffic to zero instead of capping it, because the breaker's evidence now
 * comes from here.
 *
 * <h2>Why this must be the identical call a real payment makes</h2>
 *
 * <p>A dedicated health endpoint on the provider - a {@code /ping} the
 * simulator answers unconditionally - would prove the network path is open and
 * nothing about whether {@code authorize} itself has recovered. This project's
 * mock providers inject faults at the operation level
 * ({@code psp_config.error_rate}, {@code hang_rate}), so a probe has to be an
 * {@code authorize} call through {@link PspAdapter#authorize} - the identical
 * path, gates and deadline a real payment takes - or a probe could pass while
 * every real payment still fails.
 *
 * <h2>Why a fresh reference every time, not one constant</h2>
 *
 * <p>{@code AuthorizeCommand.reference()} doubles as the provider-side
 * idempotency key (see {@code MockPspAdapter}'s own comment on this). Reusing
 * one reference across probes would let the simulator recognise the second
 * probe as a retry of the first and hand back the cached response rather than
 * re-evaluating its fault injection — which would make every probe after the
 * first one measure nothing.
 */
public class SyntheticProber {

    private static final Logger log = LoggerFactory.getLogger(SyntheticProber.class);

    /**
     * A card that was never issued to anyone. The same well-known test PAN this
     * project already uses throughout its load scripts and demos, held here as
     * a literal rather than resolved through the vault - going through
     * {@code AuthorizationService} to detokenize a real token would mean a
     * merchant's card token exists for a payment nobody made, which is the
     * one thing a synthetic probe must never produce evidence of.
     */
    private static final DetokenizedCard PROBE_CARD = new DetokenizedCard("4242424242424242", 12, 2030);
    private static final long PROBE_AMOUNT_MINOR = 100;
    private static final String PROBE_CURRENCY = "INR";

    private final ProviderConfigStore configs;
    private final CircuitBreakers breakers;
    private final PspAdapterRegistry adapters;

    private final LongAdder fired = new LongAdder();
    private final LongAdder succeeded = new LongAdder();

    public SyntheticProber(ProviderConfigStore configs, CircuitBreakers breakers,
                           PspAdapterRegistry adapters) {
        this.configs = configs;
        this.breakers = breakers;
        this.adapters = adapters;
    }

    /**
     * Runs far more often than a payment needs to, deliberately. This is the
     * knob that decides how many of the breaker's {@code permittedNumberOf-
     * CallsInHalfOpenState} probes come from here rather than from a real
     * customer who wandered in during the window. Slower than this and real
     * traffic — which, under load, arrives continuously — wins the race for
     * those permits before the probe gets a turn.
     */
    @Scheduled(fixedDelayString = "${payorch.psp.probe-interval-ms:750}")
    public void probe() {
        for (ProviderConfig config : configs.all().values()) {
            if (!config.enabled()) {
                continue;
            }
            probeIfHalfOpen(config.pspId());
        }
    }

    private void probeIfHalfOpen(String pspId) {
        // Reading state directly rather than through CircuitBreakers.call,
        // which would CREATE a breaker for a provider that has never been
        // called - the same reason ProviderHealthService reads the registry
        // map rather than going through forOperation. A provider nobody has
        // ever routed to should not acquire a breaker, a metric series, and a
        // synthetic probe cycle for a fault it has never had.
        CircuitBreaker.State state = breakers.state(pspId, "authorize");
        if (state != CircuitBreaker.State.HALF_OPEN) {
            return;
        }

        fired.increment();
        try {
            PspAdapter adapter = adapters.require(pspId);
            PspAdapter.AuthorizeCommand command = new PspAdapter.AuthorizeCommand(
                    "probe_" + UUID.randomUUID(), PROBE_AMOUNT_MINOR, PROBE_CURRENCY);

            // Deliberately not routed through breakers.call() a second time -
            // PspAdapter#authorize already wraps its own call in the same
            // breaker, bulkhead, egress limiter and deadline a real payment
            // uses. Wrapping it again here would double-count against the
            // bulkhead's permit budget for one logical call.
            adapter.authorize(command, PROBE_CARD);
            succeeded.increment();

        } catch (RuntimeException e) {
            // Expected, often. The provider is HALF_OPEN precisely because it
            // was recently broken, and "the probe failed" is a normal, useful
            // result - it is one more failure in the breaker's half-open
            // sample, which is what keeps it open until the provider actually
            // recovers. Nothing above this method needs to know it happened.
            log.debug("synthetic probe of {} did not succeed: {}", pspId, e.toString());
        }
    }

    public long fired() {
        return fired.sum();
    }

    public long succeeded() {
        return succeeded.sum();
    }
}
