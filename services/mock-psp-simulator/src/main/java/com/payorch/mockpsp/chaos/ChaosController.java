package com.payorch.mockpsp.chaos;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control plane for the simulated provider.
 *
 * <pre>{@code
 * curl -XPOST localhost:8085/_chaos -H 'content-type: application/json' \
 *      -d '{"latencyMs":0,"errorRate":0.3,"hangRate":0,"duplicateRate":0}'
 * curl localhost:8085/_chaos      # what is active
 * curl -XDELETE localhost:8085/_chaos   # back to healthy
 * }</pre>
 *
 * <p>Underscore-prefixed so it never collides with a provider path, and kept off
 * the actuator port so a k6 script and an experiment runbook can reach it
 * without extra plumbing.
 *
 * <p>{@code DELETE} exists because the single most common way to ruin an
 * experiment is to leave the previous one's chaos running. One unambiguous
 * reset, callable from a teardown hook, is worth more than the two seconds it
 * took to write.
 */
@RestController
@RequestMapping("/_chaos")
public class ChaosController {

    private final ChaosInjector injector;

    public ChaosController(ChaosInjector injector) {
        this.injector = injector;
    }

    @GetMapping
    public ChaosSettings current() {
        return injector.current();
    }

    /** @return the settings that were replaced, so a runbook can restore them */
    @PostMapping
    public ChaosSettings configure(@Valid @RequestBody ChaosSettings settings) {
        return injector.apply(settings);
    }

    @DeleteMapping
    public ChaosSettings reset() {
        return injector.reset();
    }
}
