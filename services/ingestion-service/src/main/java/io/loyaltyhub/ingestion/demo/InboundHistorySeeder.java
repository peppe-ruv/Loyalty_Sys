package io.loyaltyhub.ingestion.demo;

import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.domain.InboundStatus;
import io.loyaltyhub.ingestion.domain.MemberRef;
import io.loyaltyhub.ingestion.domain.RejectCode;
import io.loyaltyhub.ingestion.domain.RejectDetails;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storico demo del monitor ingressi (docs/servizi/ingestion-service.md §6: "40 {@code inbound_event} degli ultimi
 * 3 giorni con tutti gli esiti"; BO-26) da {@code seed/inbound-history.json}. Le date sono espressioni di docs/10 §1
 * risolte adesso; membro, envelope e dettaglio del rifiuto si calcolano come nella pipeline (§5): il {@code subject} è
 * risolto sull'indice membri già seminato, gli errori {@code INVALID_DATA} sono quelli dello schema del tipo. Righe
 * con l'esito dichiarato nel seed, nessuna pubblicazione (gli ACCEPTED sono azioni già presenti nello storico attività
 * di {@code activity-history.json}). Idempotente: le righe {@code hist-*} si cancellano e si ricaricano a ogni reset.
 * SPEC-GAP: Q-271 — solo 9 ACCEPTED, per coerenza con lo storico attività e le storie di docs/10.
 */
@Component
@Profile("demo")
public class InboundHistorySeeder {

    static final String FILE = "inbound-history.json";
    static final String EVENT_ID_PREFIX = "hist-";
    private static final String ORIGIN_EXTERNAL = "EXTERNAL";

    private final SeedLoader seed;
    private final ObjectMapper mapper;
    private final InboundEventRepository inbound;
    private final EventTypeRepository types;
    private final MemberIndexRepository members;
    private final JsonSchemaValidator validator;
    private final Clock clock;

    public InboundHistorySeeder(SeedLoader seed, ObjectMapper mapper, InboundEventRepository inbound,
                                EventTypeRepository types, MemberIndexRepository members,
                                JsonSchemaValidator validator, Clock clock) {
        this.seed = seed;
        this.mapper = mapper;
        this.inbound = inbound;
        this.types = types;
        this.members = members;
        this.validator = validator;
        this.clock = clock;
    }

    /** Ricarica lo storico; da chiamare dopo fonti, tipi e indice membri. Ritorna il numero di righe. */
    public int reseed() {
        // docs/06 §10, docs/10 §1.3: il reset tronca la tabella, non solo lo storico del seed (gli ingressi reali
        // successivi al seed restavano nel monitor e nei conteggi dopo il reset).
        inbound.deleteAll();
        int n = 0;
        for (JsonNode e : seed.readTree(FILE).path("events")) {
            insert(e);
            n++;
        }
        return n;
    }

    private void insert(JsonNode e) {
        String eventId = e.path("eventId").asString();
        if (!eventId.startsWith(EVENT_ID_PREFIX)) {
            throw new IllegalStateException(FILE + ": eventId senza prefisso " + EVENT_ID_PREFIX + ": " + eventId);
        }
        String sourceCode = e.path("source").asString();
        String shortType = e.path("type").asString();
        String fullType = LhFamily.ACTION.typePrefix() + shortType;
        String subject = e.path("subject").asString();
        Instant receivedAt = SeedDates.resolve(e.path("receivedAt").asString(), clock);
        String occurred = e.path("occurredAt").asString(null);
        Instant eventTime = occurred == null ? receivedAt : SeedDates.resolve(occurred, clock);
        InboundStatus status = InboundStatus.valueOf(e.path("status").asString());
        RejectCode code = e.hasNonNull("rejectCode") ? RejectCode.valueOf(e.path("rejectCode").asString()) : null;
        JsonNode data = e.path("data");

        String memberId = null;
        String detail = null;
        String storedSubject = subject;
        switch (status) {
            case ACCEPTED -> {
                MemberRef m = resolve(subject).orElseThrow(() -> incoherent(eventId, "membro non trovato"));
                memberId = m.memberId();
                storedSubject = "member:" + memberId; // come la pipeline (passo 8): subject normalizzato
            }
            case DUPLICATE -> {
                memberId = subject.startsWith("member:") ? subject.substring("member:".length()) : null;
                detail = RejectDetails.DUPLICATE;
            }
            case UNMATCHED -> {
                if (resolve(subject).isPresent()) {
                    throw incoherent(eventId, "UNMATCHED con un membro nell'indice");
                }
                detail = RejectDetails.unmatched(subject);
            }
            case REJECTED -> {
                if (code == null) {
                    throw incoherent(eventId, "REJECTED senza rejectCode");
                }
                switch (code) {
                    case SOURCE_DISABLED -> detail = RejectDetails.sourceDisabled(sourceCode);
                    case UNKNOWN_TYPE -> detail = RejectDetails.unknownType(shortType);
                    case TYPE_NOT_ALLOWED -> detail = RejectDetails.typeNotAllowed(sourceCode, shortType);
                    case INVALID_TIME -> detail = RejectDetails.invalidTime(eventTime.toString());
                    case INVALID_DATA -> detail = schemaErrors(eventId, shortType, data);
                    case MEMBER_NOT_ACTIVE -> {
                        MemberRef m = resolve(subject).orElseThrow(() -> incoherent(eventId, "membro non trovato"));
                        memberId = m.memberId();
                        detail = RejectDetails.memberNotActive(m.status());
                    }
                }
            }
        }
        LhEvent<JsonNode> envelope = new LhEvent<>(LhEvent.SPEC_VERSION, eventId, LhSource.source(sourceCode), fullType,
                storedSubject, eventTime, LhEvent.DATA_CONTENT_TYPE, LhSource.schemaForType(fullType, 1), LhEvent.TENANT,
                eventId, null, 0, null, data);
        inbound.insertHistory(Ulid.next(Clock.fixed(receivedAt, clock.getZone())), eventId, sourceCode, shortType,
                storedSubject, memberId, eventTime, receivedAt, status, code, detail, mapper.writeValueAsString(envelope),
                eventId, ORIGIN_EXTERNAL);
    }

    private String schemaErrors(String eventId, String shortType, JsonNode data) {
        EventType t = types.findByCode(shortType).filter(EventType::hasSchema)
                .orElseThrow(() -> incoherent(eventId, "INVALID_DATA su un tipo senza schema"));
        List<String> errors = validator.validate(t.schemaCacheKey(), t.dataSchema(), data.toString());
        if (errors.isEmpty()) {
            errors = io.loyaltyhub.ingestion.domain.CrossFieldRules.errors(shortType, data); // come la pipeline (Q-265)
        }
        if (errors.isEmpty()) {
            throw incoherent(eventId, "INVALID_DATA con dati validi per lo schema");
        }
        return String.join("; ", errors);
    }

    private Optional<MemberRef> resolve(String subject) {
        if (subject.startsWith("member:")) {
            return members.findByMemberId(subject.substring("member:".length()));
        }
        if (subject.startsWith("external:")) {
            return members.findByExternalId(subject.substring("external:".length()));
        }
        if (subject.startsWith("email:")) {
            return members.findByEmail(subject.substring("email:".length()));
        }
        return Optional.empty();
    }

    private static IllegalStateException incoherent(String eventId, String why) {
        return new IllegalStateException(FILE + ": " + eventId + " incoerente (" + why + ")");
    }
}
