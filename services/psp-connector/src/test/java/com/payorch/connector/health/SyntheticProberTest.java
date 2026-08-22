package com.payorch.connector.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.payorch.connector.config.ProviderConfig;
import com.payorch.connector.config.ProviderConfigStore;
import com.payorch.connector.provider.PspAdapter;
import com.payorch.connector.provider.PspAdapterRegistry;
import org.infra.resilience.breaker.CircuitBreakers;
import org.infra.tokenization.DetokenizedCard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mechanism experiment 09 left as a standing question: does a half-open
 * breaker get its recovery evidence from a real payment, or from here.
 *
 * <h2>What each assertion protects</h2>
 *
 * <p>{@link ProviderHealth#score} now gates a half-open breaker to 0 on the
 * premise that this class supplies the probe calls that used to come from
 * routed customer traffic. If this class silently stopped firing - a
 * dependency exception, a state-reading bug, a scheduling misconfiguration -
 * that premise would be false and a recovering provider would receive no
 * traffic of any kind, real or synthetic, until something else intervened.
 * These tests are what stands behind the gate.
 */
class SyntheticProberTest {

    private static ProviderConfig config(String pspId, boolean enabled) {
        return new ProviderConfig(pspId, pspId, "http://" + pspId, enabled,
                10, 1000, 2, 50, 30, 20, 10, 12, 20, 250, 200, Instant.now());
    }

    private ProviderConfigStore configsOf(ProviderConfig... configs) {
        Map<String, ProviderConfig> map = new LinkedHashMap<>();
        for (ProviderConfig c : configs) {
            map.put(c.pspId(), c);
        }
        ProviderConfigStore store = mock(ProviderConfigStore.class);
        when(store.all()).thenReturn(map);
        return store;
    }

    // ---------------------------------------------------------------------
    // ONLY HALF-OPEN GETS PROBED
    // ---------------------------------------------------------------------

    @Test
    void aClosedBreakerIsNeverProbed() throws Exception {
        ProviderConfigStore configs = configsOf(config("psp-a", true));
        CircuitBreakers breakers = mock(CircuitBreakers.class);
        when(breakers.state("psp-a", "authorize")).thenReturn(CircuitBreaker.State.CLOSED);
        PspAdapterRegistry adapters = mock(PspAdapterRegistry.class);

        new SyntheticProber(configs, breakers, adapters).probe();

        verify(adapters, never()).require(any());
    }

    @Test
    void anOpenBreakerIsNeverProbed() throws Exception {
        // Deliberately not probed either, and it is worth saying why: a fully
        // open breaker is not yet admitting ANY calls, synthetic or otherwise -
        // resilience4j itself refuses them with CallNotPermittedException. A
        // probe here would be a call the breaker was never going to allow,
        // wasted on a state that has not yet decided to test itself.
        ProviderConfigStore configs = configsOf(config("psp-a", true));
        CircuitBreakers breakers = mock(CircuitBreakers.class);
        when(breakers.state("psp-a", "authorize")).thenReturn(CircuitBreaker.State.OPEN);
        PspAdapterRegistry adapters = mock(PspAdapterRegistry.class);

        new SyntheticProber(configs, breakers, adapters).probe();

        verify(adapters, never()).require(any());
    }

    @Test
    void aHalfOpenBreakerIsProbed() throws Exception {
        ProviderConfigStore configs = configsOf(config("psp-a", true));
        CircuitBreakers breakers = mock(CircuitBreakers.class);
        when(breakers.state("psp-a", "authorize")).thenReturn(CircuitBreaker.State.HALF_OPEN);
        PspAdapter adapter = mock(PspAdapter.class);
        when(adapter.authorize(any(), any())).thenReturn(
                new PspAdapter.ProviderAuthorization("probe_ref", true, null, "AUTH"));
        PspAdapterRegistry adapters = mock(PspAdapterRegistry.class);
        when(adapters.require("psp-a")).thenReturn(adapter);

        SyntheticProber prober = new SyntheticProber(configs, breakers, adapters);
        prober.probe();

        verify(adapters, times(1)).require("psp-a");
        assertThat(prober.fired()).isEqualTo(1);
        assertThat(prober.succeeded()).isEqualTo(1);
    }

    @Test
    void aDisabledProviderIsNeverProbedEvenIfHalfOpen() throws Exception {
        ProviderConfigStore configs = configsOf(config("psp-a", false));
        CircuitBreakers breakers = mock(CircuitBreakers.class);
        when(breakers.state("psp-a", "authorize")).thenReturn(CircuitBreaker.State.HALF_OPEN);
        PspAdapterRegistry adapters = mock(PspAdapterRegistry.class);

        new SyntheticProber(configs, breakers, adapters).probe();

        verify(adapters, never()).require(any());
    }

    // ---------------------------------------------------------------------
    // A FAILED PROBE MUST NOT CRASH THE SCHEDULER
    // ---------------------------------------------------------------------

    /**
     * The provider is half-open precisely because it was recently broken, so a
     * probe failing is a normal and expected result, not an error. A prober
     * that let this propagate would have its {@code @Scheduled} method's
     * exception swallowed by Spring's task executor on the FIRST failed probe
     * and never run again - silently un-gating every half-open breaker in the
     * system with no synthetic evidence behind any of them.
     */
    @Test
    void aFailedProbeDoesNotThrow() throws Exception {
        ProviderConfigStore configs = configsOf(config("psp-a", true));
        CircuitBreakers breakers = mock(CircuitBreakers.class);
        when(breakers.state("psp-a", "authorize")).thenReturn(CircuitBreaker.State.HALF_OPEN);
        PspAdapter adapter = mock(PspAdapter.class);
        doThrow(new PspAdapter.ProviderUnavailableException("psp-a", null))
                .when(adapter).authorize(any(), any());
        PspAdapterRegistry adapters = mock(PspAdapterRegistry.class);
        when(adapters.require("psp-a")).thenReturn(adapter);

        SyntheticProber prober = new SyntheticProber(configs, breakers, adapters);

        assertThatCode(prober::probe).doesNotThrowAnyException();
        assertThat(prober.fired()).isEqualTo(1);
        assertThat(prober.succeeded())
                .as("a thrown authorize is a failed probe, not a successful one")
                .isZero();
    }

    // ---------------------------------------------------------------------
    // EVERY PROVIDER IS CHECKED INDEPENDENTLY
    // ---------------------------------------------------------------------

    @Test
    void oneProviderFailingDoesNotStopAnotherFromBeingProbed() throws Exception {
        ProviderConfigStore configs = configsOf(config("psp-a", true), config("psp-b", true));
        CircuitBreakers breakers = mock(CircuitBreakers.class);
        when(breakers.state("psp-a", "authorize")).thenReturn(CircuitBreaker.State.HALF_OPEN);
        when(breakers.state("psp-b", "authorize")).thenReturn(CircuitBreaker.State.HALF_OPEN);

        PspAdapter broken = mock(PspAdapter.class);
        doThrow(new PspAdapter.ProviderUnavailableException("psp-a", null))
                .when(broken).authorize(any(), any());
        PspAdapter healthy = mock(PspAdapter.class);
        when(healthy.authorize(any(), any())).thenReturn(
                new PspAdapter.ProviderAuthorization("ref", true, null, "AUTH"));

        PspAdapterRegistry adapters = mock(PspAdapterRegistry.class);
        when(adapters.require("psp-a")).thenReturn(broken);
        when(adapters.require("psp-b")).thenReturn(healthy);

        SyntheticProber prober = new SyntheticProber(configs, breakers, adapters);
        prober.probe();

        assertThat(prober.fired()).isEqualTo(2);
        assertThat(prober.succeeded())
                .as("psp-a's failure must not have skipped psp-b")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // EACH PROBE IS ITS OWN REFERENCE
    // ---------------------------------------------------------------------

    /**
     * The command's reference doubles as the provider's idempotency key. A
     * fixed reference across repeated probes would let the simulator treat the
     * second probe as a retry of the first and return its cached response
     * rather than re-evaluating fault injection - making every probe after the
     * first measure nothing.
     */
    @Test
    void twoProbesOfTheSameProviderUseDifferentReferences() throws Exception {
        ProviderConfigStore configs = configsOf(config("psp-a", true));
        CircuitBreakers breakers = mock(CircuitBreakers.class);
        when(breakers.state("psp-a", "authorize")).thenReturn(CircuitBreaker.State.HALF_OPEN);
        PspAdapter adapter = mock(PspAdapter.class);
        when(adapter.authorize(any(), any())).thenReturn(
                new PspAdapter.ProviderAuthorization("ref", true, null, "AUTH"));
        PspAdapterRegistry adapters = mock(PspAdapterRegistry.class);
        when(adapters.require("psp-a")).thenReturn(adapter);

        SyntheticProber prober = new SyntheticProber(configs, breakers, adapters);
        prober.probe();
        prober.probe();

        ArgumentCaptor<PspAdapter.AuthorizeCommand> captor =
                ArgumentCaptor.forClass(PspAdapter.AuthorizeCommand.class);
        verify(adapter, times(2)).authorize(captor.capture(), any(DetokenizedCard.class));

        assertThat(captor.getAllValues().get(0).reference())
                .isNotEqualTo(captor.getAllValues().get(1).reference());
    }
}
