package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.engagement.application.WebhookService;
import io.loyaltyhub.engagement.application.WebhookService.WebhookRequest;
import io.loyaltyhub.engagement.domain.Webhook;
import io.loyaltyhub.engagement.domain.WebhookDelivery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Webhook in uscita (docs/servizi/engagement-service.md §3; F-WBH-01, BO-23). Lettura per tutti; scritture e comandi
 * (prova, *Riprova*) solo {@code ADMIN} (capacità {@code webhook.write}, docs/08 §2). Il {@code secret} è nel corpo
 * della sola risposta di creazione. Nei path si accetta {@code id} o {@code code} (docs/06 §2).
 */
// SPEC-GAP: Q-103 — oltre alla tabella API della scheda: GET /v1/webhook-deliveries/{id} (dettaglio aggiornato di una
// consegna per BO-23) e, in demo, POST /v1/demo/jobs/deliver-webhooks?asOf= (BO-30); l'evento di prova risponde 201
// con la consegna creata e il suo primo esito. L'elenco dei webhook è un array semplice come le altre configurazioni
// di engagement (regole, template); il registro consegne è paginato {items, page}.
@RestController
public class WebhooksController {

    private final WebhookService service;

    public WebhooksController(WebhookService service) {
        this.service = service;
    }

    @GetMapping("/v1/webhooks")
    @Transactional(readOnly = true)
    public List<Webhook> list() {
        return service.list();
    }

    @GetMapping("/v1/webhooks/{id}")
    @Transactional(readOnly = true)
    public Webhook get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/v1/webhooks")
    @RequiresRole({Role.ADMIN})
    public ResponseEntity<Webhook> create(@RequestBody WebhookRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r));
    }

    @PutMapping("/v1/webhooks/{id}")
    @RequiresRole({Role.ADMIN})
    public Webhook update(@PathVariable String id, @RequestBody WebhookRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/v1/webhooks/{id}")
    @RequiresRole({Role.ADMIN})
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Registro consegne del webhook, dalla più recente; filtro {@code status}. */
    @GetMapping("/v1/webhooks/{id}/deliveries")
    @Transactional(readOnly = true)
    public PageResponse<WebhookDelivery> deliveries(@PathVariable String id,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return service.deliveries(id, status, page, size);
    }

    /** Evento di prova: crea la consegna e la tenta subito; {@code 201} con la consegna e il suo primo esito. */
    @PostMapping("/v1/webhooks/{id}/test")
    @RequiresRole({Role.ADMIN})
    public ResponseEntity<WebhookDelivery> test(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.test(id));
    }

    @GetMapping("/v1/webhook-deliveries/{id}")
    @Transactional(readOnly = true)
    public WebhookDelivery delivery(@PathVariable String id) {
        return service.delivery(id);
    }

    /** *Riprova*: un tentativo immediato di una consegna {@code FAILED}/{@code GAVE_UP}; {@code 409} altrimenti. */
    @PostMapping("/v1/webhook-deliveries/{id}/retry")
    @RequiresRole({Role.ADMIN})
    public WebhookDelivery retry(@PathVariable String id) {
        return service.retry(id);
    }
}
