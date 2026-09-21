package io.loyaltyhub.campaign.api;

import io.loyaltyhub.campaign.application.CampaignAdminService;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.engine.Evaluation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Gestione campagne (docs/servizi/campaign-service.md §3): elenco, dettaglio, creazione, transizioni, validazione, simulazione. */
@RestController
@RequestMapping("/v1/campaigns")
public class CampaignsController {

    private final CampaignAdminService service;

    public CampaignsController(CampaignAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<CampaignSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String q) {
        return service.list(status, actionType, q);
    }

    @GetMapping("/{id}")
    public Campaign get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/stats")
    public CampaignAdminService.CampaignStats stats(@PathVariable String id) {
        return service.stats(id);
    }

    @PostMapping
    public ResponseEntity<Campaign> create(@RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PostMapping("/{id}/transitions")
    public Campaign transition(@PathVariable String id, @RequestBody TransitionRequest request) {
        return service.transition(id, request);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody CreateCampaignRequest request) {
        List<String> errors = service.validate(request);
        return Map.of("valid", errors.isEmpty(), "errors", errors);
    }

    @PostMapping("/simulate")
    public Evaluation simulate(@RequestBody SimulateRequest request) {
        return service.simulate(request);
    }
}
