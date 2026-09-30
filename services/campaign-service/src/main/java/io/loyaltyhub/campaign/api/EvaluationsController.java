package io.loyaltyhub.campaign.api;

import io.loyaltyhub.campaign.infra.EvaluationLogRepository;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageParams;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Registro delle valutazioni (docs/servizi/campaign-service.md §3): spiegabilità per membro/azione. */
@RestController
@RequestMapping("/v1/evaluations")
public class EvaluationsController {

    private final EvaluationLogRepository log;

    public EvaluationsController(EvaluationLogRepository log) {
        this.log = log;
    }

    /**
     * Q-625 (opzione A): oltre a {@code memberId}, {@code outcome} e {@code limit}, filtra per {@code actionId}
     * (uguaglianza esatta) e per l'intervallo semiaperto {@code [from, to)} su {@code evaluated_at}. {@code from} e
     * {@code to} sono istanti ISO-8601 con offset (es. {@code 2026-09-30T10:00:00Z}; in query string un {@code +}
     * dell'offset va codificato {@code %2B}); il valore non interpretabile o {@code from} successivo a {@code to}
     * è un 400 {@code BAD_REQUEST}, {@code from == to} è ammesso e dà una lista vuota. Solo parametri in aggiunta:
     * la forma della risposta non cambia (regola 14, ADR-038).
     */
    @GetMapping
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
    public List<EvaluationLogRepository.EvaluationRow> list(
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String actionId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int limit) {
        int bounded = boundedLimit(limit);
        Instant fromInstant = instant("from", from);
        Instant toInstant = instant("to", to);
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw LhException.badRequest(
                    "Intervallo non valido: from (" + from.trim() + ") è successivo a to (" + to.trim() + ")");
        }
        return log.search(new EvaluationLogRepository.Filter(memberId, outcome, actionId, fromInstant, toInstant),
                bounded);
    }

    /** Q-625: {@code from}/{@code to} come istante ISO-8601 con offset (RFC 3339); assente o vuoto → nessun filtro. */
    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw LhException.badRequest("Parametro " + name + " non valido (atteso un istante ISO-8601): " + value);
        }
    }

    /**
     * SPEC-GAP: Q-532 (F2-SEC-12) — stessa regola di {@link PageParams#of}: un {@code limit} minore di 1 è un
     * «parametro errato» (400 {@code BAD_REQUEST}, mai corretto in silenzio a 1); oltre {@link PageParams#MAX_SIZE}
     * il valore è ridotto al massimo.
     */
    private static int boundedLimit(int limit) {
        if (limit < 1) {
            throw LhException.badRequest(
                    "Parametro limit non valido (atteso tra 1 e " + PageParams.MAX_SIZE + "): " + limit);
        }
        return Math.min(limit, PageParams.MAX_SIZE);
    }

    @GetMapping("/{actionId}")
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
    public String get(@PathVariable String actionId) {
        return log.findResults(actionId)
                .orElseThrow(() -> LhException.notFound("Valutazione non trovata: " + actionId));
    }
}
