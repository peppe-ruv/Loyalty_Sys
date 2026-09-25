package io.loyaltyhub.common.demo;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Endpoint base {@code POST /v1/demo/reset} (docs/06 §1, §3, §10): attivo solo col profilo {@code demo},
 * riservato ad {@code ADMIN}. Invoca ogni {@link DemoResettable} del servizio in modo idempotente, poi pubblica la
 * voce di audit {@code RESET} con l'attore (docs/06 §10). La voce è scritta <em>dopo</em> i reset: quello di insight
 * svuota l'audit e la cancellerebbe.
 */
@RestController
@RequestMapping("/v1/demo")
@RequiresRole(Role.ADMIN)
public class DemoResetController {

    private final List<DemoResettable> resettables;
    private final AuditPublisher audit;
    private final String service;

    public DemoResetController(List<DemoResettable> resettables) {
        this(resettables, null, null);
    }

    /**
     * @param audit   publisher dell'audit; {@code null} = nessuna voce (servizi senza outbox)
     * @param service nome del servizio che si azzera ({@code loyaltyhub.service}), oggetto della voce
     */
    public DemoResetController(List<DemoResettable> resettables, AuditPublisher audit, String service) {
        this.resettables = resettables;
        this.audit = audit;
        this.service = service == null || service.isBlank() ? "demo" : service;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        for (DemoResettable r : resettables) {
            r.resetToSeed();
        }
        List<String> components = resettables.stream().map(DemoResettable::demoComponent).toList();
        if (audit != null) {
            audit.record("DEMO", service, AuditEntry.Action.RESET,
                    "Reset demo di " + service + " (" + String.join(", ", components) + ")", null,
                    Map.of("components", components));
        }
        return Map.of(
                "status", "OK",
                "reset", components);
    }
}
