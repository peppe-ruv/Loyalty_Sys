package it.iren.loyalty.ingressadapters.api;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.common.idempotency.IdempotencyKeys;
import it.iren.loyalty.ingressadapters.dedup.DedupService;
import it.iren.loyalty.ingressadapters.publish.ActionPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingresso REST per le fonti (Salesforce, IrenYou, partner). Contratto: docs/contracts/openapi-ingress.yaml.
 * Risponde sempre 202 alla fonte (RI-03); gli eventi non validi finiscono nella coda di scarto con motivo.
 * Batch fino a 500 eventi (RI-04).
 */
@RestController
@RequestMapping("/v1/actions")
public class ActionIngressController {

    public record IngressItem(@NotBlank String memberId, @NotNull @Valid RewardingAction action) {}
    public record IngressBatch(@NotNull @Size(min = 1, max = 500) List<@Valid IngressItem> items) {}
    public record ItemResult(String idempotencyKey, String status, String reason) {}
    public record BatchResult(int accepted, int duplicates, int rejected, List<ItemResult> results) {}

    private final DedupService dedup;
    private final ActionPublisher publisher;

    public ActionIngressController(DedupService dedup, ActionPublisher publisher) {
        this.dedup = dedup;
        this.publisher = publisher;
    }

    @PostMapping
    public ResponseEntity<BatchResult> ingest(@RequestBody @Valid IngressBatch batch) {
        String source = "rest"; // in produzione: dal client OAuth2/HMAC autenticato (gateway → header X-Source)
        List<ItemResult> results = new ArrayList<>(batch.items().size());
        int accepted = 0, duplicates = 0, rejected = 0;
        for (IngressItem item : batch.items()) {
            RewardingAction a = item.action();
            if (!IdempotencyKeys.isValid(a.idempotencyKey())) {
                publisher.reject(source, item.memberId(), a, "INVALID_IDEMPOTENCY_KEY");
                results.add(new ItemResult(a.idempotencyKey(), "REJECTED", "INVALID_IDEMPOTENCY_KEY"));
                rejected++;
                continue;
            }
            if (a.occurredAt().isAfter(Instant.now().plusSeconds(60))) {
                publisher.reject(source, item.memberId(), a, "FUTURE_TIMESTAMP");
                results.add(new ItemResult(a.idempotencyKey(), "REJECTED", "FUTURE_TIMESTAMP"));
                rejected++;
                continue;
            }
            if (!dedup.firstSeen(a.idempotencyKey())) {
                results.add(new ItemResult(a.idempotencyKey(), "DUPLICATE", null));
                duplicates++;
                continue;
            }
            publisher.publish(CanonicalEvents.action("urn:iren:loyalty:source:" + source, item.memberId(), a));
            results.add(new ItemResult(a.idempotencyKey(), "ACCEPTED", null));
            accepted++;
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new BatchResult(accepted, duplicates, rejected, results));
    }
}
