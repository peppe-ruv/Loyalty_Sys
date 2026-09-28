package io.loyaltyhub.insight.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.insight.application.AuditChainVerifier;
import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.domain.AuditVerification;
import io.loyaltyhub.insight.infra.AuditRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Audit (docs/servizi/insight-service.md §3, docs/08 §BO-22): elenco filtrato e paginato ({@code ?page&size},
 * risposta {@code {items, page}}, docs/06 §2) e dettaglio con diff. Sola lettura; le voci arrivano dagli eventi
 * {@code io.loyaltyhub.audit.entry} consumati dall'ingest. {@code GET /v1/audit/verify} ricalcola la catena di hash
 * (F2-GRC-07, ADR-044) e dice, per servizio, se regge o dove si interrompe.
 */
@RestController
@RequestMapping("/v1/audit")
public class AuditController {

    private static final int DEFAULT_SIZE = 100;

    private final AuditRepository audits;
    private final AuditChainVerifier verifier;

    public AuditController(AuditRepository audits, AuditChainVerifier verifier) {
        this.audits = audits;
        this.verifier = verifier;
    }

    @GetMapping
    public PageResponse<AuditRecord> list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit) {
        Paging p = Paging.of(page, size, limit, DEFAULT_SIZE);
        Instant fromI = Paging.instant(from);
        Instant toI = Paging.instant(to);
        List<AuditRecord> items = audits.search(actor, role, service, entityType, entityId, action,
                fromI, toI, p.size(), p.offset());
        long total = audits.count(actor, role, service, entityType, entityId, action, fromI, toI);
        return PageResponse.of(items, p.page(), p.size(), total);
    }

    /**
     * Verifica della catena di hash dell'audit, di tutti i servizi o del solo {@code service} (404 se non ha una
     * catena). Risponde 200 anche quando una catena è interrotta: l'esito è il dato ({@code status = BROKEN}).
     */
    // SPEC-GAP: Q-399 — docs/18 §3.15 non dice chi può verificare la catena e non esiste un ruolo di revisore: si usa
    // il ruolo esistente più ristretto, ADMIN.
    @GetMapping("/verify")
    @RequiresRole({Role.ADMIN})
    public AuditVerification verifyChain(@RequestParam(required = false) String service) {
        return verifier.verify(service == null || service.isBlank() ? null : service.trim());
    }

    @GetMapping("/{id}")
    public AuditRecord byId(@PathVariable String id) {
        return audits.findById(id).orElseThrow(() -> LhException.notFound("Voce di audit non trovata: " + id));
    }
}
