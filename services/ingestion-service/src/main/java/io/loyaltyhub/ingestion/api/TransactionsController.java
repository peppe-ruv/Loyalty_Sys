package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.application.IngestionService;
import io.loyaltyhub.ingestion.application.SourceBinding;
import io.loyaltyhub.ingestion.domain.EnvelopeLimits;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Transazioni d'acquisto (F-ING-07, docs/servizi/ingestion-service.md §3): {@code POST /v1/transactions} converte un
 * ordine in un'azione {@code purchase.completed} con {@code id = "txn-" + orderId} e la fa passare dalla stessa
 * pipeline di {@code POST /v1/events} (fonte, tipo, schema, finestra temporale, dedup {@code (source, id)}, membro).
 * Stesse risposte: {@code 400} per errori di forma ({@code source}, {@code orderId}, {@code memberRef} mancanti,
 * {@code kind} sconosciuto), altrimenti {@code 202} con l'esito; {@code amount}/{@code currency} mancanti sono un
 * {@code REJECTED/INVALID_DATA} dello schema (Q-269).
 * <p>
 * Reso ({@code kind = RETURN}) → {@code purchase.returned} con {@code id = "txn-return-" + orderId}.
 * // SPEC-GAP: Q-49 — la scheda non dice come la transazione segnali il reso; scelto un campo opzionale.
 */
@RestController
@RequestMapping("/v1")
public class TransactionsController {

    private static final String PURCHASE_PREFIX = "txn-";
    private static final String RETURN_PREFIX = "txn-return-";
    /**
     * L'id dell'evento è {@code prefisso + orderId}: il limite vale per il prefisso più lungo, così un ordine e il suo
     * reso stanno entrambi in {@link EnvelopeLimits#MAX_ID}.
     */
    static final int MAX_ORDER_ID = EnvelopeLimits.MAX_ID - RETURN_PREFIX.length();

    private final IngestionService ingestion;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TransactionsController(IngestionService ingestion, ObjectMapper mapper, Clock clock) {
        this.ingestion = ingestion;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/transactions")
    // Q-492: ingresso delle fonti solo per il ruolo SOURCE (utenza di integrazione, client src-<codice>); ADMIN passa
    // per regola dell'interceptor. La fonte dichiarata deve coincidere con il client (SourceBinding).
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Esito dell'azione (una transazione): status ACCEPTED, DUPLICATE o REJECTED (la fonte non ritenta su un rifiuto di business)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = IngestResult.class))),
            @ApiResponse(responseCode = "400", description = "Errore di forma: source, orderId o memberRef mancanti, kind sconosciuto",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE: serve il ruolo SOURCE (o ADMIN); SOURCE_MISMATCH: il source"
                    + " dichiarato è diverso dal client src-<codice>, nulla è salvato né pubblicato",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @RequiresRole(Role.SOURCE)
    public ResponseEntity<IngestResult> transaction(@RequestBody TransactionRequest t) {
        requireForm(t);
        SourceBinding.requireMatch(ActorHolder.get(), t.source());
        boolean isReturn = "RETURN".equalsIgnoreCase(t.kind());

        // Q-269: amount/currency mancanti non sono un errore di forma: l'azione si costruisce senza e lo schema del
        // tipo la respinge (REJECTED/INVALID_DATA, visibile in BO-26 come per POST /v1/events).
        ObjectNode data = mapper.createObjectNode();
        data.put("orderId", t.orderId());
        if (t.amount() != null) {
            data.put("amount", t.amount());
        }
        if (!isReturn) {
            if (t.currency() != null) {
                data.put("currency", t.currency());
            }
            if (t.channel() != null) {
                data.put("channel", t.channel());
            }
            if (t.items() != null && !t.items().isNull()) {
                data.set("items", t.items());
            }
        }

        InboundEventRequest event = new InboundEventRequest(
                "1.0",
                (isReturn ? RETURN_PREFIX : PURCHASE_PREFIX) + t.orderId(),
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
        if (!missing.isEmpty()) {
            throw LhException.badRequest("Campi obbligatori mancanti: " + String.join(", ", missing));
        }
        // Senza questo controllo un orderId troppo lungo sarebbe segnalato come «id troppo lungo» dalla pipeline.
        String orderIdProblem = EnvelopeLimits.textProblem("orderId", t.orderId(), MAX_ORDER_ID);
        if (orderIdProblem != null) {
            throw LhException.badRequest(orderIdProblem + ".");
        }
        if (t.kind() != null && !t.kind().equalsIgnoreCase("PURCHASE") && !t.kind().equalsIgnoreCase("RETURN")) {
            throw LhException.badRequest("kind deve essere PURCHASE o RETURN.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
