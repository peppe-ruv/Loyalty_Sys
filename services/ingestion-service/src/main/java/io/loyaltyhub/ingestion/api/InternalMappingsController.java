package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.domain.InternalMapping;
import io.loyaltyhub.ingestion.infra.InternalMappingRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Ponte interno per il backoffice (BO-09 scheda {@code bridge}, docs/servizi/ingestion-service.md §3):
 * <ul>
 *   <li>{@code GET /v1/internal-mappings} → {@code [{factType, actionType, enabled}]} (nomi brevi del seed);</li>
 *   <li>{@code PUT /v1/internal-mappings/{factType}} con {@code {enabled}} (ADMIN, {@code program.config}) →
 *       la mappatura aggiornata; audit {@code UPDATE}. Famiglie mai mappabili (docs/05 §7) → 422
 *       {@code INTERNAL_MAPPING_FORBIDDEN}; mappatura inesistente → 404.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/internal-mappings")
public class InternalMappingsController {

    private final InternalMappingRepository mappings;
    private final AuditPublisher audit;

    public InternalMappingsController(InternalMappingRepository mappings, AuditPublisher audit) {
        this.mappings = mappings;
        this.audit = audit;
    }

    @GetMapping
    public List<InternalMapping> list() {
        return mappings.findAll();
    }

    @PutMapping("/{factType}")
    @RequiresRole({Role.ADMIN})
    @Transactional
    public InternalMapping update(@PathVariable String factType, @RequestBody InternalMappingRequest body) {
        String key = InternalMapping.shortType(factType);
        if (InternalMapping.isForbidden(key)) {
            throw LhException.validation("INTERNAL_MAPPING_FORBIDDEN",
                    "Il fatto " + key + " appartiene a una famiglia che non può diventare un'azione (docs/05 §7).");
        }
        if (body == null || body.enabled() == null) {
            throw LhException.validation("ENABLED_REQUIRED", "Il campo enabled è obbligatorio.");
        }
        InternalMapping before = mappings.find(key)
                .orElseThrow(() -> LhException.notFound("Mappatura non trovata: " + key));
        mappings.setEnabled(key, body.enabled());
        audit.record("internal_mapping", key, AuditEntry.Action.UPDATE,
                (body.enabled() ? "Abilitata" : "Disabilitata") + " la mappatura " + key + " → " + before.actionType(),
                Map.of("enabled", before.enabled()), Map.of("enabled", body.enabled()));
        return mappings.find(key).orElseThrow();
    }
}
