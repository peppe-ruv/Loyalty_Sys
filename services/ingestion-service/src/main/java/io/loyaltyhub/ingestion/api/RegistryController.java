package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Fonti per il backoffice (docs/servizi/ingestion-service.md §3, BO-09 scheda {@code sources}). Tipi azione in
 * {@link EventTypesController}.
 * <ul>
 *   <li>{@code GET /v1/sources} → elenco;</li>
 *   <li>{@code PUT /v1/sources/{code}} con {@code {enabled?, allowedTypes?}} (ADMIN, capacità {@code program.config} di
 *       docs/08 §2) → la fonte aggiornata; audit {@code UPDATE}. Fonte spenta → i suoi eventi diventano
 *       {@code REJECTED/SOURCE_DISABLED} (F-ING-05). Fonte inesistente → 404; corpo vuoto o tipo sconosciuto → 422.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class RegistryController {

    private final SourceRepository sources;
    private final EventTypeRepository types;
    private final AuditPublisher audit;

    public RegistryController(SourceRepository sources, EventTypeRepository types, AuditPublisher audit) {
        this.sources = sources;
        this.types = types;
        this.audit = audit;
    }

    @GetMapping("/sources")
    public List<Source> sources() {
        return sources.findAll();
    }

    @PutMapping("/sources/{code}")
    @RequiresRole({Role.ADMIN})
    @Transactional
    public Source update(@PathVariable String code, @RequestBody(required = false) SourceRequest body) {
        Source before = sources.findByCode(code)
                .orElseThrow(() -> LhException.notFound("Fonte non trovata: " + code));
        if (body == null || (body.enabled() == null && body.allowedTypes() == null)) {
            throw LhException.validation("SOURCE_INVALID", "Indicare enabled e/o allowedTypes.");
        }
        List<String> allowed = before.allowedTypes();
        if (body.allowedTypes() != null) {
            List<LhException.FieldError> errors = new ArrayList<>();
            LinkedHashSet<String> clean = new LinkedHashSet<>();
            for (int i = 0; i < body.allowedTypes().size(); i++) {
                String t = body.allowedTypes().get(i) == null ? "" : body.allowedTypes().get(i).trim();
                if (t.isEmpty() || types.findByCode(t).isEmpty()) {
                    errors.add(new LhException.FieldError("allowedTypes[" + i + "]", "tipo azione sconosciuto"));
                } else {
                    clean.add(t);
                }
            }
            if (!errors.isEmpty()) {
                throw LhException.validation("SOURCE_INVALID", "Tipi ammessi non validi.", errors);
            }
            allowed = List.copyOf(clean);
        }
        boolean enabled = body.enabled() == null ? before.enabled() : body.enabled();
        Source after = new Source(before.code(), before.name(), before.kind(), enabled, allowed, before.description());
        sources.upsert(after);
        String summary = before.enabled() != enabled
                ? (enabled ? "Abilitata" : "Disabilitata") + " la fonte " + code
                : "Modificati i tipi ammessi della fonte " + code;
        audit.record("source", code, AuditEntry.Action.UPDATE, summary,
                Map.of("enabled", before.enabled(), "allowedTypes", before.allowedTypes()),
                Map.of("enabled", after.enabled(), "allowedTypes", after.allowedTypes()));
        return after;
    }
}
