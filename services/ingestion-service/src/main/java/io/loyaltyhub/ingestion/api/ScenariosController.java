package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.ingestion.application.ScenarioService;
import io.loyaltyhub.ingestion.domain.Scenario;
import io.loyaltyhub.ingestion.domain.ScenarioRun;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Scenari demo (docs/servizi/ingestion-service.md §3, BO-29, F-DEMO-04): elenco, avvio asincrono e
 * avanzamento. L'avvio è riservato a chi può simulare (come il simulatore, {@code demo.simulate}).
 */
@RestController
@RequestMapping("/v1/demo")
public class ScenariosController {

    private final ScenarioService scenarios;

    public ScenariosController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping("/scenarios")
    public List<Scenario> list() {
        return scenarios.list();
    }

    @PostMapping("/scenarios/{code}/run")
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE})
    public ResponseEntity<Map<String, String>> run(@PathVariable String code) {
        String runId = scenarios.run(code, ActorHolder.get().asActorString());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId));
    }

    @GetMapping("/scenario-runs/{runId}")
    public ScenarioRun runStatus(@PathVariable String runId) {
        return scenarios.getRun(runId)
                .orElseThrow(() -> LhException.notFound("Esecuzione non trovata: " + runId));
    }
}
