package io.loyaltyhub.insight.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.insight.domain.DlqEntry;
import io.loyaltyhub.insight.infra.DlqRepository;
import io.loyaltyhub.insight.infra.IngestionClient;
import io.loyaltyhub.insight.infra.IngestionClient.IngestOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestione della DLQ (docs/servizi/insight-service.md §3, §5; F-INS-05, BO-27): <em>riprocessa</em> e <em>scarta</em>,
 * solo ADMIN (il controllo di ruolo è sul controller). Ogni chiusura è auditata su {@code lh.audit.v1}.
 * <ul>
 *   <li><strong>riprocessa</strong>: solo per le azioni ({@code 409 NOT_REPROCESSABLE} per effetti e fatti) → re-invio a
 *       {@code ingestion POST /v1/events} con lo stesso {@code id} (ADR-002 eccezione 1). La chiamata HTTP avviene
 *       fuori transazione; la voce passa a {@code REPROCESSED} solo se ingestion risponde {@code ACCEPTED}.</li>
 *   <li><strong>scarta</strong>: qualunque voce aperta, con una nota obbligatoria (§5 "solo discard con nota").</li>
 * </ul>
 */
// SPEC-GAP: Q-109 — insight §4 dice "nessun evento su Kafka", ma le scritture da backoffice vanno auditate (F-AUD-01,
// docs/05 §6): riprocessa/scarta pubblicano la sola voce di audit su lh.audit.v1 (via outbox), nessun fatto di dominio.
@Service
public class DlqService {

    /** Attributi CloudEvents che ingestion richiede in ingresso (docs/05 §2); gli {@code lh*} li rimette lei. */
    private static final List<String> INBOUND_FIELDS = List.of("specversion", "id", "source", "type", "subject", "time", "data");
    private static final String ENTITY_TYPE = "DlqEntry";

    private final DlqRepository repo;
    private final IngestionClient ingestion;
    private final AuditPublisher audit;
    private final TransactionTemplate tx;

    public DlqService(DlqRepository repo, IngestionClient ingestion, AuditPublisher audit,
                      PlatformTransactionManager txManager) {
        this.repo = repo;
        this.ingestion = ingestion;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    public DlqEntry get(String id) {
        return repo.findById(id).orElseThrow(() -> LhException.notFound("Voce DLQ non trovata: " + id));
    }

    public DlqEntry reprocess(String id, String note) {
        DlqEntry entry = requireOpen(id);
        if (!"ACTION".equals(entry.family())) {
            throw LhException.conflict("NOT_REPROCESSABLE",
                    "Solo le azioni si riprocessano da qui: effetti e fatti si possono solo scartare con una nota");
        }
        String actor = ActorHolder.get().asActorString();
        IngestOutcome outcome = ingestion.resend(inboundEvent(entry.payload()), actor, entry.id());
        if (!"ACCEPTED".equals(outcome.status())) {
            // La voce resta aperta: ingestion non ha ripubblicato l'azione.
            throw LhException.conflict("REPROCESS_REJECTED", "ingestion non ha accettato il re-invio: " + outcome.status()
                    + (outcome.rejectCode() == null ? "" : " / " + outcome.rejectCode())
                    + (outcome.detail() == null ? "" : " — " + outcome.detail()));
        }
        return close(entry, DlqEntry.REPROCESSED, actor, blankToNull(note),
                "DLQ riprocessata: " + label(entry));
    }

    public DlqEntry discard(String id, String note) {
        // SPEC-GAP: Q-110 — §5 chiede la nota per scartare effetti e fatti; la si chiede per ogni voce (più prudente).
        if (note == null || note.isBlank()) {
            throw LhException.validation("NOTE_REQUIRED", "Per scartare una voce DLQ serve una nota");
        }
        DlqEntry entry = requireOpen(id);
        return close(entry, DlqEntry.DISCARDED, ActorHolder.get().asActorString(), note.trim(),
                "DLQ scartata: " + label(entry));
    }

    private DlqEntry close(DlqEntry entry, String status, String actor, String note, String summary) {
        return tx.execute(s -> {
            if (!repo.resolve(entry.id(), status, actor, note)) {
                throw notOpen(get(entry.id()));
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", status);
            if (note != null) {
                after.put("note", note);
            }
            audit.record(ENTITY_TYPE, entry.id(), AuditEntry.Action.TRANSITION, summary,
                    Map.of("status", DlqEntry.OPEN), after);
            return get(entry.id());
        });
    }

    private DlqEntry requireOpen(String id) {
        DlqEntry entry = get(id);
        if (!DlqEntry.OPEN.equals(entry.status())) {
            throw notOpen(entry);
        }
        return entry;
    }

    // SPEC-GAP: Q-107 — la scheda non dice cosa risponde una seconda chiusura: 409 DLQ_NOT_OPEN, voce invariata.
    private static LhException notOpen(DlqEntry entry) {
        return LhException.conflict("DLQ_NOT_OPEN", "La voce DLQ è già chiusa (" + entry.status() + ")");
    }

    /** Il CloudEvent da re-inviare: gli attributi d'ingresso dell'envelope originale, stesso {@code id}. */
    static Map<String, Object> inboundEvent(JsonNode payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : INBOUND_FIELDS) {
            JsonNode v = payload == null ? null : payload.get(field);
            if (v != null && !v.isNull()) {
                out.put(field, v.isString() ? v.asString() : v);
            }
        }
        return out;
    }

    private static String label(DlqEntry e) {
        String type = e.shortType() == null ? e.eventId() : e.shortType();
        return type + " (" + e.consumer() + ", " + (e.errorCode() == null ? "?" : e.errorCode()) + ")";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
