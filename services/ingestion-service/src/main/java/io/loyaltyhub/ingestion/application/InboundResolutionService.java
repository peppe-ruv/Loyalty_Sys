package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.domain.Evaluation;
import io.loyaltyhub.ingestion.domain.InboundResolution;
import io.loyaltyhub.ingestion.domain.InboundResolution.Kind;
import io.loyaltyhub.ingestion.domain.InboundStatus;
import io.loyaltyhub.ingestion.domain.MemberRef;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import io.loyaltyhub.ingestion.infra.InboundEventRepository.InboundRow;
import io.loyaltyhub.ingestion.infra.InboundEventRepository.StoredInbound;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Eventi non abbinati e respinti (F-ING-04, F-ING-09; docs/servizi/ingestion-service.md §3, BO-26, M7.4):
 * <ul>
 *   <li><b>Riprova</b> ({@code POST /v1/inbound-events/{id}/retry}) — solo {@code REJECTED}/{@code UNMATCHED}: rivaluta
 *       il payload salvato con la stessa pipeline (stessa fonte, stesso id);</li>
 *   <li><b>Abbina</b> ({@code POST /v1/inbound-events/{id}/match}) — solo {@code UNMATCHED}: il membro indicato
 *       dall'operatore sostituisce la risoluzione del subject; deve esistere in {@code member_index} ed essere ACTIVE;</li>
 *   <li><b>Abbinamento automatico</b> — alla registrazione di un membro ({@code member.registered}) i parcheggiati il
 *       cui subject ora risolve a lui passano dallo stesso percorso.</li>
 * </ul>
 * Se la rivalutazione accetta, la riga diventa {@code ACCEPTED} <em>in place</em> e l'azione va in outbox nella stessa
 * transazione (stesso id del CloudEvent, {@code lhcorrelationid} = id, {@code lhhop} 0, come un ingresso normale).
 * La riga è bloccata ({@code FOR UPDATE}) per tutta la transazione e l'indice unico parziale sugli ACCEPTED fa da
 * rete: un secondo tentativo trova la riga già {@code ACCEPTED} → {@code 409}, mai una doppia pubblicazione.
 */
// SPEC-GAP: Q-118 — la scheda non dice se riprova/abbina creino una nuova riga o aggiornino quella esistente, né cosa
// resti se la rivalutazione fallisce: si aggiorna la riga (l'ingresso è uno solo; il subject originale resta, il payload
// diventa l'azione pubblicata) e un esito ancora negativo sostituisce il precedente (anche per Abbina, se nel frattempo
// fallisce un passo diverso dal membro, es. fonte disabilitata). Chi/come/quando in resolution/resolved_by/resolved_at.
@Service
public class InboundResolutionService {

    private static final Logger log = LoggerFactory.getLogger(InboundResolutionService.class);
    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };
    static final String ENTITY_TYPE = "inbound_event";
    static final String SYSTEM_ACTOR = "system";

    /**
     * Finestra dell'abbinamento automatico: la conservazione di {@code inbound_event} (7 giorni, §2).
     * SPEC-GAP: Q-115 — la scheda non fissa limiti: al massimo {@link #AUTO_MATCH_LIMIT} righe per registrazione.
     */
    static final Duration AUTO_MATCH_WINDOW = Duration.ofDays(7);
    static final int AUTO_MATCH_LIMIT = 100;

    private final InboundEventRepository inbound;
    private final MemberIndexRepository memberIndex;
    private final IngestionService pipeline;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public InboundResolutionService(InboundEventRepository inbound, MemberIndexRepository memberIndex,
                                    IngestionService pipeline, OutboxWriter outbox, AuditPublisher audit,
                                    ObjectMapper mapper, Clock clock) {
        this.inbound = inbound;
        this.memberIndex = memberIndex;
        this.pipeline = pipeline;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** <em>Riprova</em>: rivaluta una riga {@code REJECTED}/{@code UNMATCHED}; restituisce la riga aggiornata. */
    @Transactional
    public InboundRow retry(String id) {
        StoredInbound stored = lock(id);
        InboundStatus status = InboundResolution.parseStatus(stored.row().status());
        if (!InboundResolution.canRetry(status)) {
            throw LhException.conflict("INBOUND_NOT_RETRYABLE",
                    "Si riprovano solo gli eventi respinti o non abbinati: questo è " + stored.row().status() + ".");
        }
        Evaluation ev = pipeline.evaluate(requestOf(stored), null);
        apply(stored, ev, Kind.RETRY, ActorHolder.get().asActorString());
        return reload(id);
    }

    /** <em>Abbina a un membro</em>: solo {@code UNMATCHED}; il membro deve esistere ed essere attivo. */
    @Transactional
    public InboundRow match(String id, String memberId) {
        String target = memberId == null ? "" : memberId.trim();
        if (target.isEmpty()) {
            throw LhException.validation("MEMBER_REQUIRED", "Indica il membro a cui abbinare l'evento.",
                    List.of(new LhException.FieldError("memberId", "obbligatorio")));
        }
        StoredInbound stored = lock(id);
        if (!InboundResolution.canMatch(InboundResolution.parseStatus(stored.row().status()))) {
            throw LhException.conflict("INBOUND_NOT_UNMATCHED",
                    "Si abbinano solo gli eventi non abbinati: questo è " + stored.row().status() + ".");
        }
        MemberRef member = memberIndex.findByMemberId(target).orElseThrow(() -> LhException.validation(
                "MEMBER_NOT_FOUND", "Membro " + target + " non trovato.",
                List.of(new LhException.FieldError("memberId", "membro inesistente"))));
        if (!member.isActive()) {
            throw LhException.validation("MEMBER_NOT_ACTIVE",
                    "Il membro " + target + " non è attivo (" + member.status() + "): non riceve azioni.",
                    List.of(new LhException.FieldError("memberId", "membro non attivo")));
        }
        Evaluation ev = pipeline.evaluate(requestOf(stored), member.memberId());
        apply(stored, ev, Kind.MANUAL_MATCH, ActorHolder.get().asActorString());
        return reload(id);
    }

    /**
     * Abbinamento automatico alla registrazione del membro (F-ING-04): le righe {@code UNMATCHED} degli ultimi
     * {@link #AUTO_MATCH_WINDOW} con subject {@code external:<externalId>} o {@code email:<email>} passano dalla
     * pipeline. Gira dentro la transazione del consumer idempotente del fatto: una riconsegna non rifà nulla
     * ({@code processed_event}) e comunque le righe già accettate non sono più {@code UNMATCHED}.
     * Solo l'esito {@code ACCEPTED} tocca la riga: gli altri (fonte nel frattempo disabilitata, membro non attivo…)
     * la lasciano parcheggiata per l'operatore. SPEC-GAP: Q-116.
     *
     * @return quante righe sono state accettate
     */
    @Transactional
    public int autoMatch(String memberId, String externalId, String email) {
        InboundResolution.AutoMatchKeys keys = InboundResolution.autoMatchKeys(externalId, email);
        if (keys.isEmpty()) {
            return 0;
        }
        Instant since = clock.instant().minus(AUTO_MATCH_WINDOW);
        int accepted = 0;
        for (String id : inbound.findUnmatchedIds(keys.externalSubject(), keys.emailSubject(), since, AUTO_MATCH_LIMIT)) {
            StoredInbound stored = inbound.lockForResolution(id).orElse(null);
            if (stored == null || !InboundResolution.canMatch(InboundResolution.parseStatus(stored.row().status()))) {
                continue; // risolta nel frattempo (riprova/abbina manuale)
            }
            Evaluation ev = pipeline.evaluate(requestOf(stored), null);
            if (ev.accepted() && memberId.equals(ev.memberId())) {
                apply(stored, ev, Kind.AUTO_MATCH, SYSTEM_ACTOR);
                accepted++;
            }
        }
        if (accepted > 0) {
            log.info("Abbinamento automatico: {} eventi non abbinati accettati per {}", accepted, memberId);
        }
        return accepted;
    }

    // ---------- interni ----------

    private StoredInbound lock(String id) {
        return inbound.lockForResolution(id)
                .orElseThrow(() -> LhException.notFound("Evento non trovato: " + id));
    }

    private InboundRow reload(String id) {
        return inbound.findById(id).orElseThrow(() -> LhException.notFound("Evento non trovato: " + id));
    }

    /** La richiesta originale ricostruita dall'envelope salvato (stessa fonte, stesso id, subject originale). */
    private InboundEventRequest requestOf(StoredInbound stored) {
        LhEvent<JsonNode> e = mapper.readValue(stored.payloadJson(), EVENT_TYPE);
        Instant time = e.time() != null ? e.time() : stored.eventTime();
        return new InboundEventRequest(e.specversion(), e.id(), e.source(), e.type(), e.subject(),
                time == null ? null : time.toString(), e.data());
    }

    /**
     * Applica l'esito alla riga. {@code ACCEPTED} → riga accettata + outbox; altro esito → la riga resta non accettata
     * con il nuovo esito ({@code DUPLICATE} se nel frattempo un ingresso con la stessa fonte+id è stato accettato:
     * SPEC-GAP: Q-114 — la riga esce dalla coda senza ripubblicare). Ogni azione manuale è auditata.
     */
    private void apply(StoredInbound stored, Evaluation ev, Kind kind, String actor) {
        InboundRow before = stored.row();
        String id = before.id();
        if (ev.accepted()) {
            boolean updated = inbound.markAccepted(id, ev.memberId(), pipeline.serialize(ev.event()), ev.correlationId(),
                    kind.name(), actor, clock.instant());
            if (!updated) {
                throw LhException.conflict("INBOUND_NOT_RETRYABLE", "L'evento è già stato risolto.");
            }
            outbox.write(ev.event());
        } else {
            inbound.updateOutcome(id, ev.status(), ev.rejectCode(),
                    ev.detail() != null ? ev.detail() : duplicateDetail(ev), ev.memberId());
        }
        auditResolution(before, ev, kind, actor);
    }

    private static String duplicateDetail(Evaluation ev) {
        return ev.status() == InboundStatus.DUPLICATE
                ? "Un evento con la stessa fonte e lo stesso id è già stato accettato." : null;
    }

    /**
     * Voce di audit su {@code lh.audit.v1} (docs/05 §6), come le altre modifiche di gestione del servizio (fonti, tipi,
     * mapping). SPEC-GAP: Q-117 — la scheda (§4) elenca l'audit solo per fonti/tipi/mapping: riprova e abbina sono
     * scritture da backoffice, quindi auditate anche loro (F-AUD-01); l'abbinamento automatico come job di sistema.
     */
    private void auditResolution(InboundRow before, Evaluation ev, Kind kind, String actor) {
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        diff(b, a, "status", before.status(), ev.status().name());
        diff(b, a, "rejectCode", before.rejectCode(), ev.rejectCode() == null ? null : ev.rejectCode().name());
        diff(b, a, "memberId", before.memberId(), ev.memberId());
        if (ev.accepted()) {
            a.put("resolution", kind.name());
            a.put("correlationId", ev.correlationId());
        }
        String summary = switch (kind) {
            case RETRY -> "Riprovato l'evento in ingresso " + before.eventId() + " (" + before.sourceCode() + "): "
                    + before.status() + " → " + ev.status();
            case MANUAL_MATCH -> "Abbinato l'evento in ingresso " + before.eventId() + " (" + before.sourceCode()
                    + ") al membro " + ev.memberId() + ": " + before.status() + " → " + ev.status();
            case AUTO_MATCH -> "Abbinamento automatico dell'evento in ingresso " + before.eventId() + " ("
                    + before.sourceCode() + ") al nuovo membro " + ev.memberId();
        };
        if (SYSTEM_ACTOR.equals(actor)) {
            audit.recordJob(ENTITY_TYPE, before.id(), summary, b, a);
        } else {
            audit.record(ENTITY_TYPE, before.id(), AuditEntry.Action.TRANSITION, summary, b, a);
        }
    }

    private static void diff(Map<String, Object> before, Map<String, Object> after, String field, Object b, Object a) {
        if (!Objects.equals(b, a)) {
            before.put(field, b);
            after.put(field, a);
        }
    }
}
