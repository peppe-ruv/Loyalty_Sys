package io.loyaltyhub.common.demo;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Endpoint base {@code POST /v1/demo/reset} (docs/06 §1, §3): attivo solo col profilo {@code demo},
 * riservato ad {@code ADMIN}. Invoca ogni {@link DemoResettable} del servizio in modo idempotente.
 */
@RestController
@RequestMapping("/v1/demo")
@RequiresRole(Role.ADMIN)
public class DemoResetController {

    private final List<DemoResettable> resettables;

    public DemoResetController(List<DemoResettable> resettables) {
        this.resettables = resettables;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        for (DemoResettable r : resettables) {
            r.resetToSeed();
        }
        return Map.of(
                "status", "OK",
                "reset", resettables.stream().map(DemoResettable::demoComponent).toList());
    }
}
