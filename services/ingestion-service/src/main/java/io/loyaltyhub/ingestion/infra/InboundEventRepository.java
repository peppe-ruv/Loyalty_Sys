package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.InboundStatus;
import io.loyaltyhub.ingestion.domain.RejectCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** Persistenza di {@code inbound_event} (docs/servizi/ingestion-service.md §2, §5). */
@Repository
public class InboundEventRepository {

    private final JdbcClient jdbc;

    public InboundEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Esiste già un evento ACCETTATO per questa coppia (fonte, id)? (dedup, docs §5 punto 6). */
    public boolean acceptedExists(String sourceCode, String eventId) {
        Long count = jdbc.sql("SELECT count(*) FROM inbound_event WHERE source_code = ? AND event_id = ? AND status = 'ACCEPTED'")
                .params(sourceCode, eventId).query(Long.class).single();
        return count > 0;
    }

    /**
     * Inserisce l'evento accettato; {@code false} se un altro accettato con stessa (fonte, id) esiste già
     * (indice parziale unico) — gara concorrente trattata come duplicato.
     */
    public boolean insertAccepted(String id, String eventId, String sourceCode, String typeCode, String subject,
                                  String memberId, Instant eventTime, String payloadJson, String correlationId, String origin) {
        int inserted = jdbc.sql("""
                        INSERT INTO inbound_event
                          (id, event_id, source_code, type_code, subject, member_id, event_time,
                           status, payload, correlation_id, origin)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', cast(? AS jsonb), ?, ?)
                        ON CONFLICT (source_code, event_id) WHERE status = 'ACCEPTED' DO NOTHING
                        """)
                .params(id, eventId, sourceCode, typeCode, subject, memberId,
                        java.sql.Timestamp.from(eventTime), payloadJson, correlationId, origin)
                .update();
        return inserted == 1;
    }

    /** Registra un esito non accettato (REJECTED / UNMATCHED) — sempre inserito, non soggetto a dedup. */
    public void saveOutcome(String id, String eventId, String sourceCode, String typeCode, String subject,
                            String memberId, Instant eventTime, InboundStatus status, RejectCode rejectCode,
                            String rejectDetail, String payloadJson, String correlationId, String origin) {
        jdbc.sql("""
                        INSERT INTO inbound_event
                          (id, event_id, source_code, type_code, subject, member_id, event_time,
                           status, reject_code, reject_detail, payload, correlation_id, origin)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?)
                        """)
                .params(id, eventId, sourceCode, typeCode, subject, memberId,
                        java.sql.Timestamp.from(eventTime), status.name(),
                        rejectCode == null ? null : rejectCode.name(), rejectDetail,
                        payloadJson, correlationId, origin)
                .update();
    }
}
