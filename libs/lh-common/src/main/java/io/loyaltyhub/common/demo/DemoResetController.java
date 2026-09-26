package io.loyaltyhub.common.demo;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Endpoint demo comuni (docs/06 §1, §3, §10): attivi solo col profilo {@code demo}, riservati ad {@code ADMIN}.
 * <ul>
 *   <li>{@code POST /v1/demo/reset}: invoca ogni {@link DemoResettable} del servizio in modo idempotente, poi pubblica la
 *       voce di audit {@code RESET} con l'attore (docs/06 §10). La voce è scritta <em>dopo</em> i reset: quello di insight
 *       svuota l'audit e la cancellerebbe. Due reset contemporanei sono eseguiti uno dopo l'altro (SPEC-GAP: Q-P7): il
 *       secondo riparte dal seed e l'esito è lo stesso di uno solo.</li>
 *   <li>{@code GET /v1/demo/info}: profili attivi, versione, conteggi per tabella degli schemi del servizio e ultimo reset
 *       (BO-30).</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/demo")
@RequiresRole(Role.ADMIN)
public class DemoResetController {

    private final List<DemoResettable> resettables;
    private final AuditPublisher audit;
    private final String service;
    private final Environment env;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile Instant lastReset;

    public DemoResetController(List<DemoResettable> resettables) {
        this(resettables, null, null);
    }

    /**
     * @param audit   publisher dell'audit; {@code null} = nessuna voce (servizi senza outbox)
     * @param service nome del servizio che si azzera ({@code loyaltyhub.service}), oggetto della voce
     */
    public DemoResetController(List<DemoResettable> resettables, AuditPublisher audit, String service) {
        this(resettables, audit, service, null, null, Clock.systemUTC());
    }

    public DemoResetController(List<DemoResettable> resettables, AuditPublisher audit, String service,
                               Environment env, JdbcClient jdbc, Clock clock) {
        this.resettables = resettables;
        this.audit = audit;
        this.service = service == null || service.isBlank() ? "demo" : service;
        this.env = env;
        this.jdbc = jdbc;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        lock.lock();
        try {
            for (DemoResettable r : resettables) {
                r.resetToSeed();
            }
            List<String> components = resettables.stream().map(DemoResettable::demoComponent).toList();
            if (audit != null) {
                audit.record("DEMO", service, AuditEntry.Action.RESET,
                        "Reset demo di " + service + " (" + String.join(", ", components) + ")", null,
                        Map.of("components", components));
            }
            lastReset = clock.instant();
            return Map.of(
                    "status", "OK",
                    "reset", components);
        } finally {
            lock.unlock();
        }
    }

    /** Stato del servizio per BO-30 (docs/06 §10): {@code lastReset} assente se nessun reset dall'avvio. */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        out.put("profiles", env == null ? List.of() : Arrays.asList(env.getActiveProfiles()));
        out.put("version", version());
        out.put("components", resettables.stream().map(DemoResettable::demoComponent).toList());
        out.put("counts", counts());
        out.put("lastReset", lastReset);
        return out;
    }

    private String version() {
        String configured = env == null ? null : env.getProperty("loyaltyhub.version");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String implementation = DemoResetController.class.getPackage().getImplementationVersion();
        return implementation != null ? implementation : "sviluppo";
    }

    /** Righe di ogni tabella degli schemi del servizio ({@code search_path}), esclusa la storia di Flyway. */
    private Map<String, Long> counts() {
        Map<String, Long> out = new TreeMap<>();
        if (jdbc == null) {
            return out;
        }
        List<String> tables = jdbc.sql("""
                        SELECT quote_ident(table_schema) || '.' || quote_ident(table_name) FROM information_schema.tables
                        WHERE table_type = 'BASE TABLE' AND table_schema = ANY (current_schemas(false))
                          AND table_name <> 'flyway_schema_history'
                        ORDER BY 1
                        """).query(String.class).list();
        for (String t : tables) {
            out.put(t.replace("\"", ""), jdbc.sql("SELECT count(*) FROM " + t).query(Long.class).single());
        }
        return out;
    }
}
