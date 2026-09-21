package io.loyaltyhub.campaign.api;

import io.loyaltyhub.campaign.infra.EvaluationLogRepository;
import io.loyaltyhub.common.web.LhException;
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
    public List<EvaluationLogRepository.EvaluationRow> list(
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String outcome,
            @RequestParam(defaultValue = "50") int limit) {
        return log.search(memberId, outcome, Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping("/{actionId}")
    public String get(@PathVariable String actionId) {
        return log.findResults(actionId)
                .orElseThrow(() -> LhException.notFound("Valutazione non trovata: " + actionId));
    }
}
