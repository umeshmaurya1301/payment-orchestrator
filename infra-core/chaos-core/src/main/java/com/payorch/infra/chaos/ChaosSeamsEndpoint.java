package com.payorch.infra.chaos;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * Runtime control of the seams, over actuator.
 *
 * <pre>{@code
 * curl localhost:8081/actuator/chaosseams
 * curl -XPOST localhost:8081/actuator/chaosseams/payment-row-lock \
 *      -H 'content-type: application/json' -d '{"action":"PAUSE","pauseMs":5000}'
 * curl -XDELETE localhost:8081/actuator/chaosseams
 * }</pre>
 *
 * <p>An actuator endpoint rather than a {@code @RestController} for two
 * reasons. It sits on the management surface alongside Chaos Monkey's own
 * {@code /actuator/chaosmonkey}, so there is one place to look; and it is not
 * exposed unless a service explicitly lists it in
 * {@code management.endpoints.web.exposure.include}, which makes shipping the
 * seam machinery to somewhere it should not be reachable an explicit act rather
 * than an accident.
 */
@Endpoint(id = "chaosseams")
public class ChaosSeamsEndpoint {

    private final ChaosSeams seams;

    public ChaosSeamsEndpoint(ChaosSeams seams) {
        this.seams = seams;
    }

    @ReadOperation
    public Map<String, ChaosSeam> armed() {
        return seams.armed();
    }

    /**
     * @param action  {@code PAUSE} or {@code FAIL}
     * @param pauseMs ignored unless {@code PAUSE}. {@code @Nullable} is not
     *        decoration: actuator treats every operation parameter as mandatory
     *        unless it is marked nullable, so without it, arming a {@code FAIL}
     *        seam is rejected with "Missing parameters: pauseMs" for a value
     *        that has no meaning in that case.
     */
    @WriteOperation
    public Map<String, ChaosSeam> arm(@Selector String name,
                                      ChaosSeam.Action action,
                                      @Nullable Long pauseMs) {
        seams.arm(name, action == ChaosSeam.Action.FAIL
                ? ChaosSeam.fail()
                : ChaosSeam.pause(pauseMs == null ? 1000 : pauseMs));
        return seams.armed();
    }

    @DeleteOperation
    public Map<String, ChaosSeam> disarmAll() {
        seams.disarmAll();
        return seams.armed();
    }
}
