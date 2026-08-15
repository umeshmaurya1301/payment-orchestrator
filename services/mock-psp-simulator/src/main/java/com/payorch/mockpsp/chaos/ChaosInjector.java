package com.payorch.mockpsp.chaos;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;

import com.payorch.infra.logging.LogEvent;
import com.payorch.infra.logging.LogFields;
import com.payorch.infra.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Holds the current chaos configuration and applies it to a request.
 *
 * <p>State lives in an {@link AtomicReference}, not in configuration
 * properties, because every experiment reconfigures the provider mid-run
 * without a restart - a restart would reset connection pools and circuit
 * breakers, which are exactly the things being measured.
 */
@Component
public class ChaosInjector {

    private static final Logger log = LoggerFactory.getLogger(ChaosInjector.class);

    private final AtomicReference<ChaosSettings> settings = new AtomicReference<>(ChaosSettings.healthy());

    /**
     * Released on shutdown so hung requests do not outlive the process.
     *
     * <p>A hang has to be indistinguishable from a dead provider for the caller
     * and still let the container stop. Sleeping would survive a graceful
     * shutdown and hold the JVM open until Docker's grace period expired,
     * turning every experiment teardown into a ten-second wait.
     */
    private final CountDownLatch shutdown = new CountDownLatch(1);

    public ChaosSettings current() {
        return settings.get();
    }

    public ChaosSettings apply(ChaosSettings updated) {
        ChaosSettings previous = settings.getAndSet(updated);
        log.info("chaos reconfigured",
                LogEvent.event()
                        .with(LogFields.OPERATION, "chaos")
                        .with(LogFields.LATENCY_MS, updated.latencyMs())
                        .with(LogFields.OUTCOME, updated.isHealthy() ? "healthy" : "degraded")
                        .args());
        return previous;
    }

    public ChaosSettings reset() {
        return apply(ChaosSettings.healthy());
    }

    /**
     * Applies latency, hangs and errors, in that order of precedence.
     *
     * <p>The order is deliberate. A hang is checked first because it subsumes
     * everything after it - a provider that never answers does not get as far as
     * choosing a status code. Latency is applied before the error roll because a
     * real provider that is failing is usually also slow, and a caller that
     * times out before seeing the 500 is a genuinely different failure from one
     * that sees it immediately.
     *
     * @throws ApiException a 500, when the error roll wins. Deliberately an
     *         {@code ApiException} rather than a bespoke type: it renders through
     *         the shared RFC-7807 handler and is logged at WARN without a stack
     *         trace. An injected fault firing thousands of times during a chaos
     *         run must not bury the real errors under stack traces.
     */
    public void beforeResponse() {
        ChaosSettings active = settings.get();

        if (roll(active.hangRate())) {
            hangForever();
        }
        if (active.latencyMs() > 0) {
            sleep(active.latencyMs());
        }
        if (roll(active.errorRate())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "provider_error",
                    "the provider failed (injected)");
        }
    }

    /** Whether this authorization should be processed again rather than replayed. */
    public boolean shouldDuplicate() {
        return roll(settings.get().duplicateRate());
    }

    @PreDestroy
    void releaseHungRequests() {
        shutdown.countDown();
    }

    private void hangForever() {
        try {
            // Bounded only so a leaked thread cannot outlive a day. From the
            // caller's point of view this is indistinguishable from a provider
            // that has stopped answering, which is the whole point: a client
            // with no read timeout waits here forever.
            shutdown.await(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw shuttingDown();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw shuttingDown();
        }
    }

    private static ApiException shuttingDown() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider_shutdown",
                "the injected hang was released because the provider is shutting down");
    }

    private static boolean roll(double probability) {
        return probability > 0 && ThreadLocalRandom.current().nextDouble() < probability;
    }
}
