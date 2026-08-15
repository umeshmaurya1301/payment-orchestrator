package com.payorch.infra.chaos;

/**
 * The in-process chaos layer's settings, mutable at runtime.
 *
 * <p>This is the layer that breaks the <em>bean</em>. Toxiproxy breaks the link
 * and Pumba breaks the process; neither can express "this service's own service
 * layer got slow", which is what a garbage-collection pause, a lock contention
 * problem or a badly-behaved library actually looks like from the outside.
 *
 * @param latencyMs      delay added to a targeted call
 * @param latencyRate    probability, 0..1, that a targeted call is delayed
 * @param exceptionRate  probability, 0..1, that a targeted call throws instead
 *
 * @see BeanAssaultAspect for what "targeted" means
 */
public record BeanAssault(long latencyMs, double latencyRate, double exceptionRate) {

    public static BeanAssault off() {
        return new BeanAssault(0, 0, 0);
    }

    public BeanAssault {
        latencyRate = clamp(latencyRate);
        exceptionRate = clamp(exceptionRate);
        if (latencyMs < 0) {
            latencyMs = 0;
        }
    }

    public boolean isActive() {
        return (latencyMs > 0 && latencyRate > 0) || exceptionRate > 0;
    }

    private static double clamp(double rate) {
        if (rate < 0) {
            return 0;
        }
        return Math.min(rate, 1);
    }
}
