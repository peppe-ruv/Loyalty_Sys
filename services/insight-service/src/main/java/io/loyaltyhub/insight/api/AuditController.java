package io.loyaltyhub.insight.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.infra.AuditRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Audit (docs/servizi/insight-service.md §3, docs/08 §BO-22): elenco filtrato e dettaglio con diff.
 * Sola lettura; le voci arrivano dagli eventi {@code io.loyaltyhub.audit.entry} consumati dall'ingest.
 */
@RestController
@RequestMapping("/v1/audit")
public class AuditController {

    private static final int MAX_LIMIT = 200;

    private final AuditRepository audits;

    public AuditController(AuditRepository audits) {
        this.audits = audits;
    }

    public record AuditPage(List<AuditRecord> items, int count, long total) {
    }

    @GetMapping
    public AuditPage list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "100") int limit,
            @RequestParam(required = false, defaultValue = "0") int page) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int offset = Math.max(page, 0) * capped;
        Instant fromI = parseInstant(from);
        Instant toI = parseInstant(to);
        List<AuditRecord> items = audits.search(actor, role, service, entityType, entityId, action,
                fromI, toI, capped, offset);
        long total = audits.count(actor, role, service, entityType, entityId, action, fromI, toI);
        return new AuditPage(items, items.size(), total);
    }

    @GetMapping("/{id}")
    public AuditRecord byId(@PathVariable String id) {
        return audits.findById(id).orElseThrow(() -> LhException.notFound("Voce di audit non trovata: " + id));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            throw LhException.badRequest("Istante non valido (atteso ISO-8601): " + value);
        }
    }
}
