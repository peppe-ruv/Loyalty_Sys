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

import java.util.List;

/** Registro delle valutazioni (docs/servizi/campaign-service.md §3): spiegabilità per membro/azione. */
@RestController
@RequestMapping("/v1/evaluations")
public class EvaluationsController {

    private final EvaluationLogRepository log;

    public EvaluationsController(EvaluationLogRepository log) {
        this.log = log;
    }

    @GetMapping
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
    public List<EvaluationLogRepository.EvaluationRow> list(
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String outcome,
            @RequestParam(defaultValue = "50") int limit) {
        return log.search(memberId, outcome, boundedLimit(limit));
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
