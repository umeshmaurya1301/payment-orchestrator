package com.payorch.infra.chaos;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The layer that replaced Chaos Monkey. These tests are what establish that it
 * actually injects something - which is precisely the property Chaos Monkey
 * turned out not to have on Boot 4, and which nothing in its wiring revealed.
 */
class BeanAssaultAspectTest {

    private BeanAssaultAspect aspect;
    private Target target;

    /** Stands in for one of our own {@code @Service} beans. */
    @Service
    static class Target {

        String work() {
            return "done";
        }
    }

    @BeforeEach
    void setUp() {
        aspect = new BeanAssaultAspect();
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
        factory.addAspect(aspect);
        target = factory.getProxy();
    }

    @Test
    void injectsNothingWhenOff() {
        assertThat(target.work()).isEqualTo("done");
        assertThat(aspect.injectedLatencies()).isZero();
        assertThat(aspect.injectedExceptions()).isZero();
    }

    @Test
    void injectsLatencyWhenActive() {
        aspect.apply(new BeanAssault(150, 1.0, 0));

        long startedAt = System.nanoTime();
        assertThat(target.work()).isEqualTo("done");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(120);
        assertThat(aspect.injectedLatencies()).isEqualTo(1);
    }

    @Test
    void injectsExceptionsWhenActive() {
        aspect.apply(new BeanAssault(0, 0, 1.0));

        assertThatThrownBy(target::work)
                .isInstanceOf(BeanAssaultAspect.BeanAssaultException.class)
                .hasMessageContaining("work");
        assertThat(aspect.injectedExceptions()).isEqualTo(1);
    }

    @Test
    void resetStopsInjecting() {
        aspect.apply(new BeanAssault(0, 0, 1.0));
        aspect.reset();

        assertThat(target.work()).isEqualTo("done");
    }

    /**
     * The counters are what distinguish "the assault fired and the system
     * absorbed it" from "the assault was never on". Mistaking the second for
     * the first is how a chaos run produces a confident, wrong conclusion.
     */
    @Test
    void countersRecordWhatWasActuallyInjected() {
        aspect.apply(new BeanAssault(1, 1.0, 0));
        target.work();
        target.work();

        assertThat(aspect.injectedLatencies()).isEqualTo(2);
    }

    @Test
    void ratesAreClampedToSaneValues() {
        assertThat(new BeanAssault(100, 5.0, -1).latencyRate()).isEqualTo(1.0);
        assertThat(new BeanAssault(100, 5.0, -1).exceptionRate()).isZero();
        assertThat(new BeanAssault(-5, 0.5, 0).latencyMs()).isZero();
    }

    @Test
    void anAssaultWithZeroLatencyAndZeroRatesIsInactive() {
        assertThat(BeanAssault.off().isActive()).isFalse();
        assertThat(new BeanAssault(500, 0, 0).isActive())
                .as("latency with a zero rate never fires")
                .isFalse();
        assertThat(new BeanAssault(500, 0.1, 0).isActive()).isTrue();
        assertThat(new BeanAssault(0, 0, 0.1).isActive()).isTrue();
    }

    @Test
    void theEndpointAppliesAndResets() {
        BeanAssaultEndpoint endpoint = new BeanAssaultEndpoint(aspect);

        endpoint.apply(250L, 1.0, 0.0);
        assertThat(aspect.current()).isEqualTo(new BeanAssault(250, 1.0, 0));

        assertThat(endpoint.current())
                .containsEntry("latencyMs", 250L)
                .containsEntry("active", true);

        endpoint.reset();
        assertThat(aspect.current().isActive()).isFalse();
    }

    /** The advice must pass through whatever the target returned. */
    @Test
    void proceedResultIsPassedThrough() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("value");

        assertThat(aspect.assault(joinPoint)).isEqualTo("value");
    }
}
