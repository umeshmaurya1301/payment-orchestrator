package com.payorch.infra.chaos;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * Runtime control of the bean assault.
 *
 * <pre>{@code
 * curl localhost:8081/actuator/chaosbeans
 * curl -XPOST localhost:8081/actuator/chaosbeans \
 *      -H 'content-type: application/json' \
 *      -d '{"latencyMs":2000,"latencyRate":1.0,"exceptionRate":0}'
 * curl -XDELETE localhost:8081/actuator/chaosbeans
 * }</pre>
 *
 * <p>A plain {@code @Endpoint}, not {@code @RestControllerEndpoint}. That is the
 * annotation Chaos Monkey uses and the reason its control surface 404s on Boot
 * 4: the annotation class still ships, but nothing auto-configures a discoverer
 * for it any more, so the endpoint registers as an ordinary bean and is never
 * mapped. The modern annotation is not merely preferred here - it is the one
 * that works.
 */
@Endpoint(id = "chaosbeans")
public class BeanAssaultEndpoint {

    private final BeanAssaultAspect aspect;

    public BeanAssaultEndpoint(BeanAssaultAspect aspect) {
        this.aspect = aspect;
    }

    @ReadOperation
    public Map<String, Object> current() {
        return describe(aspect.current());
    }

    /** @return the settings that were replaced, so a runbook can restore them */
    @WriteOperation
    public Map<String, Object> apply(Long latencyMs, Double latencyRate, Double exceptionRate) {
        BeanAssault previous = aspect.apply(new BeanAssault(
                latencyMs == null ? 0 : latencyMs,
                latencyRate == null ? 0 : latencyRate,
                exceptionRate == null ? 0 : exceptionRate));
        return describe(previous);
    }

    @DeleteOperation
    public Map<String, Object> reset() {
        return describe(aspect.reset());
    }

    private Map<String, Object> describe(BeanAssault assault) {
        // LinkedHashMap so the shape of the response is stable between calls -
        // an experiment log that diffs two captures should show what changed,
        // not what got reordered.
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("latencyMs", assault.latencyMs());
        view.put("latencyRate", assault.latencyRate());
        view.put("exceptionRate", assault.exceptionRate());
        view.put("active", assault.isActive());
        // Counters are of the aspect, not of the snapshot: they say whether
        // anything was actually injected, which is the difference between "the
        // assault did nothing" and "the assault was never on".
        view.put("injectedLatencies", aspect.injectedLatencies());
        view.put("injectedExceptions", aspect.injectedExceptions());
        return view;
    }
}
