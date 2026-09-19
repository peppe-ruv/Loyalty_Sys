package io.loyaltyhub.ingestion.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.loyaltyhub.ingestion.domain.InboundStatus;
import io.loyaltyhub.ingestion.domain.MemberRef;
import io.loyaltyhub.ingestion.domain.RejectCode;
import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Pipeline di accettazione delle azioni premianti (docs/servizi/ingestion-service.md §5), in ordine;
 * al primo fallimento si salva {@code inbound_event} con l'esito e ci si ferma:
 * <ol>
 *   <li>forma envelope valida → altrimenti {@code 400}, nulla salvato;</li>
 *   <li>fonte esistente e abilitata → {@code REJECTED/SOURCE_DISABLED};</li>
 *   <li>tipo noto, abilitato e ammesso per la fonte → {@code UNKNOWN_TYPE} / {@code TYPE_NOT_ALLOWED};</li>
 *   <li>{@code data} valido contro lo schema → {@code REJECTED/INVALID_DATA};</li>
 *   <li>{@code time} non oltre 5 min nel futuro né più vecchio di 30 giorni → {@code INVALID_TIME};</li>
 *   <li>dedup {@code (source, id)} → {@code DUPLICATE};</li>
 *   <li>membro risolto e {@code ACTIVE} → {@code UNMATCHED} / {@code MEMBER_NOT_ACTIVE};</li>
 *   <li>arricchimento {@code lh*} → outbox su {@code lh.actions.v1} → {@code ACCEPTED}.</li>
 * </ol>
 * Un duplicato è tollerato, un'azione persa no (CLAUDE.md §3): dedup + outbox nella stessa transazione.
 */
@Service
public class IngestionService {

    private static final Duration MAX_FUTURE = Duration.ofMinutes(5);
    private static final Duration MAX_PAST = Duration.ofDays(30);
    private static final String ORIGIN_EXTERNAL = "EXTERNAL";

    private final InboundEventRepository inbound;
    private final SourceRepository sources;
    private final EventTypeRepository eventTypes;
    private final MemberIndexRepository memberIndex;
    private final JsonSchemaValidator schemaValidator;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public IngestionService(InboundEventRepository inbound, SourceRepository sources,
                            EventTypeRepository eventTypes, MemberIndexRepository memberIndex,
                            JsonSchemaValidator schemaValidator, OutboxWriter outbox,
                            ObjectMapper mapper, Clock clock) {
        this.inbound = inbound;
        this.sources = sources;
        this.eventTypes = eventTypes;
        this.memberIndex = memberIndex;
        this.schemaValidator = schemaValidator;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public IngestResult ingest(InboundEventRequest request) {
        // 1. forma envelope → 400 (nulla salvato).
        validateForm(request);
        Instant time = parseTime(request.time());

        String eventId = request.id();
        String subject = request.subject();
        String correlationId = eventId;
        String fullType = normalizeType(request.type());
        String shortType = shortType(fullType);
        String sourceCode = sourceCodeOf(normalizeSource(request.source()));

        // 2. fonte esistente e abilitata.
        Optional<Source> source = sources.findByCode(sourceCode);
        if (source.isEmpty() || !source.get().enabled()) {
            return reject(request, time, sourceCode, shortType, subject, null,
                    RejectCode.SOURCE_DISABLED, "Fonte sconosciuta o disabilitata: " + sourceCode);
        }

        // 3. tipo noto, abilitato e ammesso per la fonte.
        Optional<EventType> type = eventTypes.findByCode(shortType);
        if (type.isEmpty() || !type.get().enabled()) {
            return reject(request, time, sourceCode, shortType, subject, null,
                    RejectCode.UNKNOWN_TYPE, "Tipo azione sconosciuto o disabilitato: " + shortType);
        }
        if (!source.get().allows(shortType)) {
            return reject(request, time, sourceCode, shortType, subject, null,
                    RejectCode.TYPE_NOT_ALLOWED, "Tipo non ammesso per la fonte " + sourceCode + ": " + shortType);
        }

        // 4. data valido contro lo schema del tipo.
        if (type.get().hasSchema()) {
            List<String> errors = schemaValidator.validate(shortType, type.get().dataSchema(), request.data().toString());
            if (!errors.isEmpty()) {
                return reject(request, time, sourceCode, shortType, subject, null,
                        RejectCode.INVALID_DATA, String.join("; ", errors));
            }
        }

        // 5. time nella finestra ammessa.
        Instant now = clock.instant();
        if (time.isAfter(now.plus(MAX_FUTURE)) || time.isBefore(now.minus(MAX_PAST))) {
            return reject(request, time, sourceCode, shortType, subject, null,
                    RejectCode.INVALID_TIME, "time fuori finestra (max +5 min, -30 giorni): " + request.time());
        }

        // 6. dedup (source, id).
        if (inbound.acceptedExists(sourceCode, eventId)) {
            return IngestResult.duplicate(eventId, memberIdOf(subject), correlationId);
        }

        // 7. risoluzione membro.
        Optional<MemberRef> member = resolveMember(subject);
        if (member.isEmpty()) {
            return saveUnmatched(request, time, sourceCode, shortType, subject);
        }
        if (!member.get().isActive()) {
            return reject(request, time, sourceCode, shortType, subject, member.get().memberId(),
                    RejectCode.MEMBER_NOT_ACTIVE, "Membro non attivo (" + member.get().status() + ")");
        }

        // 8. arricchimento → outbox → ACCEPTED.
        String memberId = member.get().memberId();
        String normalizedSubject = "member:" + memberId;
        LhEvent<JsonNode> event = enrich(eventId, request.source(), fullType, normalizedSubject, time,
                correlationId, request.data());

        boolean inserted = inbound.insertAccepted(Ulid.next(clock), eventId, sourceCode, shortType,
                normalizedSubject, memberId, time, serialize(event), correlationId, ORIGIN_EXTERNAL);
        if (!inserted) {
            // Gara concorrente sullo stesso (fonte, id): trattata come duplicato, nessuna doppia pubblicazione.
            return IngestResult.duplicate(eventId, memberId, correlationId);
        }
        outbox.write(event);
        return IngestResult.accepted(eventId, memberId, correlationId);
    }

    // ---------- passi ----------

    private void validateForm(InboundEventRequest r) {
        require(r.specversion(), "specversion");
        if (!LhEvent.SPEC_VERSION.equals(r.specversion())) {
            throw LhException.badRequest("specversion deve essere " + LhEvent.SPEC_VERSION);
        }
        require(r.id(), "id");
        require(r.source(), "source");
        require(r.type(), "type");
        require(r.subject(), "subject");
        require(r.time(), "time");
        if (r.data() == null || r.data().isNull()) {
            throw LhException.badRequest("data è obbligatorio");
        }
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw LhException.badRequest("Campo obbligatorio mancante: " + field);
        }
    }

    private Instant parseTime(String time) {
        try {
            return Instant.parse(time);
        } catch (DateTimeParseException e) {
            throw LhException.badRequest("time non è un istante RFC 3339 valido: " + time);
        }
    }

    /** Risolve il membro dal {@code subject}: {@code member:<id>} / {@code external:<x>} / {@code email:<x>}. */
    private Optional<MemberRef> resolveMember(String subject) {
        if (subject.startsWith("member:")) {
            return memberIndex.findByMemberId(subject.substring("member:".length()));
        }
        if (subject.startsWith("external:")) {
            return memberIndex.findByExternalId(subject.substring("external:".length()));
        }
        if (subject.startsWith("email:")) {
            return memberIndex.findByEmail(subject.substring("email:".length()));
        }
        // Nessun prefisso riconosciuto: tentativo indulgente come id membro.
        return memberIndex.findByMemberId(subject);
    }

    private IngestResult reject(InboundEventRequest r, Instant time, String sourceCode, String shortType,
                                String subject, String memberId, RejectCode code, String detail) {
        LhEvent<JsonNode> event = enrich(r.id(), r.source(), normalizeType(r.type()), subject, time, r.id(), r.data());
        inbound.saveOutcome(Ulid.next(clock), r.id(), sourceCode, shortType, subject, memberId, time,
                InboundStatus.REJECTED, code, detail, serialize(event), r.id(), ORIGIN_EXTERNAL);
        return IngestResult.rejected(r.id(), memberId, r.id(), code, detail);
    }

    private IngestResult saveUnmatched(InboundEventRequest r, Instant time, String sourceCode,
                                       String shortType, String subject) {
        LhEvent<JsonNode> event = enrich(r.id(), r.source(), normalizeType(r.type()), subject, time, r.id(), r.data());
        inbound.saveOutcome(Ulid.next(clock), r.id(), sourceCode, shortType, subject, null, time,
                InboundStatus.UNMATCHED, null, "Membro non trovato per subject " + subject,
                serialize(event), r.id(), ORIGIN_EXTERNAL);
        return IngestResult.unmatched(r.id(), r.id());
    }

    /** Envelope CloudEvent canonico: id/correlation dalla fonte, hop 0, source come URN, actor nullo. */
    private LhEvent<JsonNode> enrich(String eventId, String source, String fullType, String subject,
                                     Instant time, String correlationId, JsonNode data) {
        return new LhEvent<>(
                LhEvent.SPEC_VERSION, eventId, normalizeSource(source), fullType, subject, time,
                LhEvent.DATA_CONTENT_TYPE, LhSource.schemaForType(fullType, 1), LhEvent.TENANT,
                correlationId, null, 0, null, data);
    }

    // ---------- normalizzazioni ----------

    /** {@code purchase.completed} → {@code io.loyaltyhub.action.purchase.completed}; type completo invariato. */
    private String normalizeType(String type) {
        return type.startsWith(LhFamily.TYPE_PREFIX) ? type : LhFamily.ACTION.typePrefix() + type;
    }

    private String shortType(String fullType) {
        return fullType.startsWith(LhFamily.ACTION.typePrefix())
                ? fullType.substring(LhFamily.ACTION.typePrefix().length())
                : fullType;
    }

    private String normalizeSource(String source) {
        return source.startsWith(LhSource.SOURCE_PREFIX) ? source : LhSource.source(source);
    }

    private String sourceCodeOf(String sourceUrn) {
        int last = sourceUrn.lastIndexOf(':');
        return last >= 0 ? sourceUrn.substring(last + 1) : sourceUrn;
    }

    private String memberIdOf(String subject) {
        return subject.startsWith("member:") ? subject.substring("member:".length()) : null;
    }

    private String serialize(LhEvent<JsonNode> event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw LhException.badRequest("payload non serializzabile");
        }
    }
}
