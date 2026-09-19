package io.loyaltyhub.ingestion.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.loyaltyhub.ingestion.domain.InboundStatus;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import io.loyaltyhub.common.ids.Ulid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Accettazione ridotta (M0.5): forma valida → dedup {@code (source, id)} → arricchimento {@code lh*} →
 * outbox su {@code lh.actions.v1}. Fonti, tipi, schema e risoluzione membro arrivano in M1.1
 * (docs/servizi/ingestion-service.md §5). Un duplicato è tollerato, un'azione persa no (CLAUDE.md §3).
 */
@Service
public class IngestionService {

    private final InboundEventRepository repository;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public IngestionService(InboundEventRepository repository, OutboxWriter outbox, ObjectMapper mapper) {
        this.repository = repository;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public IngestResult ingest(InboundEventRequest request) {
        validateForm(request);
        Instant time = parseTime(request.time());

        String fullType = normalizeType(request.type());
        String sourceUrn = normalizeSource(request.source());
        String sourceCode = sourceCodeOf(sourceUrn);
        String subject = normalizeSubject(request.subject());
        String memberId = memberIdOf(subject);
        String correlationId = request.id();

        LhEvent<JsonNode> event = new LhEvent<>(
                LhEvent.SPEC_VERSION, request.id(), sourceUrn, fullType, subject, time,
                LhEvent.DATA_CONTENT_TYPE, LhSource.schemaForType(fullType, 1), LhEvent.TENANT,
                correlationId, null, 0, null, request.data());

        boolean isNew = repository.insertIfNew(
                Ulid.next(), request.id(), sourceCode, shortType(fullType), subject, memberId, time,
                InboundStatus.ACCEPTED, serialize(event), correlationId, "EXTERNAL");

        if (!isNew) {
            return new IngestResult(request.id(), InboundStatus.DUPLICATE, memberId, correlationId);
        }
        outbox.write(event);
        return new IngestResult(request.id(), InboundStatus.ACCEPTED, memberId, correlationId);
    }

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
        return source.startsWith("urn:loyaltyhub:") ? source : LhSource.source(source);
    }

    private String sourceCodeOf(String sourceUrn) {
        int last = sourceUrn.lastIndexOf(':');
        return last >= 0 ? sourceUrn.substring(last + 1) : sourceUrn;
    }

    private String normalizeSubject(String subject) {
        return subject.startsWith("member:") || subject.contains(":") ? subject : "member:" + subject;
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
