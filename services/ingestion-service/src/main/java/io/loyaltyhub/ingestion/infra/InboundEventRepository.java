package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.InboundStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** Persistenza di {@code inbound_event} (docs/servizi/ingestion-service.md §2). */
@Repository
public class InboundEventRepository {

    private final JdbcClient jdbc;

    public InboundEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserisce l'evento in ingresso; {@code false} se {@code (source_code, event_id)} esiste già (duplicato).
     * {@code ON CONFLICT DO NOTHING}: atomico, tollera il replay (dedup del punto 6, docs §5).
     */
    public boolean insertIfNew(String id, String eventId, String sourceCode, String typeCode,
                               String subject, String memberId, Instant eventTime,
                               InboundStatus status, String payloadJson, String correlationId, String origin) {
        int inserted = jdbc.sql("""
                        INSERT INTO inbound_event
                          (id, event_id, source_code, type_code, subject, member_id, event_time,
                           status, payload, correlation_id, origin)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?)
                        ON CONFLICT (source_code, event_id) DO NOTHING
                        """)
                .params(id, eventId, sourceCode, typeCode, subject, memberId,
                        java.sql.Timestamp.from(eventTime), status.name(), payloadJson, correlationId, origin)
                .update();
        return inserted == 1;
    }
}
