package io.loyaltyhub.ingestion.api;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.application.IngestionService;
import io.loyaltyhub.ingestion.domain.IngestResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Transazioni d'acquisto (F-ING-07, docs/servizi/ingestion-service.md §3): {@code POST /v1/transactions} converte un
 * ordine in un'azione {@code purchase.completed} con {@code id = "txn-" + orderId} e la fa passare dalla stessa
 * pipeline di {@code POST /v1/events} (fonte, tipo, schema, finestra temporale, dedup {@code (source, id)}, membro).
 * Stesse risposte: {@code 400} per errori di forma, altrimenti {@code 202} con l'esito.
 * <p>
 * Reso ({@code kind = RETURN}) → {@code purchase.returned} con {@code id = "txn-return-" + orderId}.
 * // SPEC-GAP: Q-49 — la scheda non dice come la transazione segnali il reso; scelto un campo opzionale.
 */
@RestController
@RequestMapping("/v1")
public class TransactionsController {

    private final IngestionService ingestion;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TransactionsController(IngestionService ingestion, ObjectMapper mapper, Clock clock) {
        this.ingestion = ingestion;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/transactions")
    public ResponseEntity<IngestResult> transaction(@RequestBody TransactionRequest t) {
        requireForm(t);
        boolean isReturn = "RETURN".equalsIgnoreCase(t.kind());

        ObjectNode data = mapper.createObjectNode();
        data.put("orderId", t.orderId());
        data.put("amount", t.amount());
        if (!isReturn) {
            data.put("currency", t.currency());
            if (t.channel() != null) {
                data.put("channel", t.channel());
            }
            if (t.items() != null && !t.items().isNull()) {
                data.set("items", t.items());
            }
        }

        InboundEventRequest event = new InboundEventRequest(
                "1.0",
                (isReturn ? "txn-return-" : "txn-") + t.orderId(),
                t.source(),
                isReturn ? "purchase.returned" : "purchase.completed",
                t.memberRef(),
                t.occurredAt() != null ? t.occurredAt() : clock.instant().toString(),
                data);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestion.ingest(event));
    }

    private static void requireForm(TransactionRequest t) {
        if (t == null) {
            throw LhException.badRequest("Corpo della transazione mancante.");
        }
        List<String> missing = new ArrayList<>();
        if (blank(t.source())) missing.add("source");
        if (blank(t.orderId())) missing.add("orderId");
        if (blank(t.memberRef())) missing.add("memberRef");
        if (t.amount() == null) missing.add("amount");
        if (!"RETURN".equalsIgnoreCase(t.kind()) && blank(t.currency())) missing.add("currency");
        if (!missing.isEmpty()) {
            throw LhException.badRequest("Campi obbligatori mancanti: " + String.join(", ", missing));
        }
        if (t.kind() != null && !t.kind().equalsIgnoreCase("PURCHASE") && !t.kind().equalsIgnoreCase("RETURN")) {
            throw LhException.badRequest("kind deve essere PURCHASE o RETURN.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
