package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.loyaltyhub.ingestion.domain.ItemOutcome;
import io.loyaltyhub.ingestion.domain.OutcomeCounts;
import io.loyaltyhub.ingestion.domain.RejectCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static io.loyaltyhub.ingestion.domain.ImportParser.attribute;

/**
 * Ingresso batch (F2-ING-01, docs/18 §3.6, docs/servizi/ingestion-service.md §3): {@code POST /v1/events/batch} con fino
 * a {@value #MAX_EVENTS} CloudEvent, con esito per elemento. Ogni elemento passa dalla stessa pipeline di
 * {@code POST /v1/events} (validazione, dedup, risoluzione del membro, non abbinati, outbox) in una propria transazione:
 * un elemento respinto o non valido non annulla gli altri, e ciò che è accettato è pubblicato una sola volta.
 * <p>
 * Un elemento che per {@code POST /v1/events} sarebbe un {@code 400} (forma dell'envelope, {@code source} non URN,
 * Q-258) qui è l'esito {@code INVALID}: nulla salvato nel monitor, come il {@code 400}. Errori dell'intera richiesta:
 * corpo non array → {@code 400}; array vuoto → {@code 422 BATCH_EMPTY}; oltre {@value #MAX_EVENTS} →
 * {@code 422 BATCH_TOO_LARGE} (nessun elemento elaborato). Un errore imprevisto su un elemento annulla solo quello
 * ({@code INVALID} con {@value #INTERNAL_ERROR_DETAIL}), mai un {@code 500} dopo elementi già confermati.
 */
@Service
public class BatchIngestionService {

    public static final int MAX_EVENTS = 1000;
    /** Dettaglio di un elemento non elaborato per un errore interno: nulla salvato, la fonte può reinviarlo. */
    public static final String INTERNAL_ERROR_DETAIL =
            "Errore interno: elemento non elaborato, nulla salvato; si può reinviare";

    private static final Logger log = LoggerFactory.getLogger(BatchIngestionService.class);

    /** Esito di un elemento; {@code index} è la posizione nell'array (da 0). */
    public record BatchItem(int index, String eventId, ItemOutcome status, String memberId, String correlationId,
                            RejectCode rejectCode, String detail) {
    }

    /** Risposta: conteggi per esito ed esiti nell'ordine della richiesta. */
    public record BatchResult(int total, OutcomeCounts counts, List<BatchItem> items) {
    }

    private final IngestionService ingestion;

    public BatchIngestionService(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Forma dell'intera richiesta, prima di elaborare qualunque elemento: ritorna il numero di elementi (per il limite di
     * frequenza, che conta gli eventi).
     */
    public static int requireBatch(JsonNode body) {
        if (body == null || !body.isArray()) {
            throw LhException.badRequest("Il corpo deve essere un array JSON di CloudEvent (formato batch)");
        }
        if (body.isEmpty()) {
            throw LhException.validation("BATCH_EMPTY", "Il batch non contiene eventi.");
        }
        if (body.size() > MAX_EVENTS) {
            throw LhException.validation("BATCH_TOO_LARGE", "Al massimo " + MAX_EVENTS + " eventi per batch: ricevuti "
                    + body.size() + ". Dividi l'invio in più richieste.");
        }
        return body.size();
    }

    public BatchResult ingest(JsonNode body) {
        requireBatch(body);
        List<BatchItem> items = new ArrayList<>(body.size());
        OutcomeCounts counts = OutcomeCounts.ZERO;
        int index = 0;
        for (JsonNode node : body) {
            BatchItem item = one(index++, node);
            counts = counts.plus(item.status());
            items.add(item);
        }
        return new BatchResult(items.size(), counts, items);
    }

    private BatchItem one(int index, JsonNode node) {
        if (node == null || !node.isObject()) {
            return new BatchItem(index, null, ItemOutcome.INVALID, null, null, null,
                    "L'elemento non è un oggetto JSON (CloudEvent)");
        }
        InboundEventRequest request = new InboundEventRequest(attribute(node, "specversion"), attribute(node, "id"),
                attribute(node, "source"), attribute(node, "type"), attribute(node, "subject"), attribute(node, "time"),
                node.get("data"));
        try {
            ingestion.checkExternalForm(request);
        } catch (LhException e) {
            return new BatchItem(index, request.id(), ItemOutcome.INVALID, null, null, null, e.getMessage());
        }
        IngestionService.Tracked tracked;
        try {
            tracked = ingestion.ingestTracked(request, IngestionService.ORIGIN_EXTERNAL);
        } catch (RuntimeException e) {
            // La transazione dell'elemento è annullata (nulla salvato, nulla pubblicato); gli elementi già confermati
            // restano, quindi la risposta resta 202 con l'esito di ciascuno invece di un 500 dopo scritture parziali.
            log.warn("Batch: elemento {} non elaborato per un errore interno: {}", index, e.toString());
            return new BatchItem(index, request.id(), ItemOutcome.INVALID, null, null, null, INTERNAL_ERROR_DETAIL);
        }
        IngestResult r = tracked.result();
        return new BatchItem(index, r.eventId(), ItemOutcome.of(r.status()), r.memberId(), r.correlationId(),
                r.rejectCode(), tracked.detail());
    }
}
