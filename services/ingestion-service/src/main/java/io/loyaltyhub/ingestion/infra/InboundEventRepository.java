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

    /** Riga del monitor ingressi (docs/servizi/ingestion-service.md §3, BO-26). */
    public record InboundRow(String id, String eventId, String sourceCode, String typeCode, String subject,
                             String memberId, java.time.Instant receivedAt, String status, String rejectCode,
                             String rejectDetail, String correlationId, String origin, String resolution,
                             String resolvedBy, java.time.Instant resolvedAt) {
    }

    /** Riga completa per il dettaglio (BO-26: "CloudEvent completo") e per la rivalutazione (retry / match). */
    public record StoredInbound(InboundRow row, Instant eventTime, String payloadJson) {
    }

    private static final String COLUMNS = """
            id, event_id, source_code, type_code, subject, member_id, received_at,
            status, reject_code, reject_detail, correlation_id, origin, resolution, resolved_by, resolved_at
            """;

    /**
     * Filtri del monitor (docs/servizi/ingestion-service.md §3: {@code status, source, type, memberId, from, to, q}).
     * {@code from}/{@code to} delimitano {@code received_at} (estremi inclusi); {@code q} è un testo cercato senza
     * maiuscole in id evento, soggetto, membro, tipo, fonte, correlazione e dettaglio del rifiuto.
     * SPEC-GAP: Q-272 — la scheda elenca i filtri senza la semantica.
     */
    public record Filter(String source, String type, String memberId, Instant from, Instant to, String q) {
        public static Filter of(String source, String type, String memberId) {
            return new Filter(source, type, memberId, null, null, null);
        }
    }

    public java.util.List<InboundRow> search(String status, String source, String type, String memberId, int limit) {
        return search(status, Filter.of(source, type, memberId), limit);
    }

    public java.util.List<InboundRow> search(String status, Filter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM inbound_event WHERE 1 = 1\n");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        appendFilters(sql, args, filter);
        sql.append(" ORDER BY received_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.sql(sql.toString()).params(args).query(InboundEventRepository::mapRow).list();
    }

    /**
     * Conteggi per esito (schede di BO-26, F-ING-09) con gli stessi filtri dell'elenco, esito escluso: le quattro
     * chiavi di {@link InboundStatus} ci sono sempre, in quell'ordine, anche a zero.
     */
    public java.util.Map<String, Long> countByStatus(Filter filter) {
        StringBuilder sql = new StringBuilder("SELECT status, count(*) AS n FROM inbound_event WHERE 1 = 1\n");
        java.util.List<Object> args = new java.util.ArrayList<>();
        appendFilters(sql, args, filter);
        sql.append(" GROUP BY status");
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (InboundStatus s : InboundStatus.values()) {
            counts.put(s.name(), 0L);
        }
        jdbc.sql(sql.toString()).params(args).query((rs, n) -> {
            String status = rs.getString("status");
            if (counts.containsKey(status)) {
                counts.put(status, rs.getLong("n"));
            }
            return status;
        }).list();
        return counts;
    }

    private static void appendFilters(StringBuilder sql, java.util.List<Object> args, Filter f) {
        if (f.source() != null && !f.source().isBlank()) {
            sql.append(" AND source_code = ?");
            args.add(f.source().trim());
        }
        if (f.type() != null && !f.type().isBlank()) {
            sql.append(" AND type_code = ?");
            args.add(f.type().trim());
        }
        if (f.memberId() != null && !f.memberId().isBlank()) {
            sql.append(" AND member_id = ?");
            args.add(f.memberId().trim());
        }
        if (f.from() != null) {
            sql.append(" AND received_at >= ?");
            args.add(java.sql.Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND received_at <= ?");
            args.add(java.sql.Timestamp.from(f.to()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            String like = "%" + f.q().trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append("""
                     AND (lower(event_id) LIKE ? OR lower(subject) LIKE ? OR lower(coalesce(member_id, '')) LIKE ?
                       OR lower(type_code) LIKE ? OR lower(source_code) LIKE ? OR lower(coalesce(correlation_id, '')) LIKE ?
                       OR lower(coalesce(reject_detail, '')) LIKE ?)
                    """);
            for (int i = 0; i < 7; i++) {
                args.add(like);
            }
        }
    }

    public java.util.Optional<InboundRow> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM inbound_event WHERE id = ?")
                .param(id).query(InboundEventRepository::mapRow).optional();
    }

    /** Riga completa (payload e istante dell'evento) per il dettaglio. */
    public java.util.Optional<StoredInbound> findStored(String id) {
        return jdbc.sql("SELECT " + COLUMNS + ", event_time, payload::text AS payload FROM inbound_event WHERE id = ?")
                .param(id).query(InboundEventRepository::mapStored).optional();
    }

    /**
     * Come {@link #findStored} ma blocca la riga ({@code FOR UPDATE}) fino a fine transazione: due <em>riprova</em> /
     * <em>abbina</em> concorrenti (o l'abbinamento automatico) sulla stessa riga si serializzano, e il secondo vede
     * l'esito del primo — mai una doppia pubblicazione.
     */
    public java.util.Optional<StoredInbound> lockForResolution(String id) {
        return jdbc.sql("SELECT " + COLUMNS + ", event_time, payload::text AS payload FROM inbound_event WHERE id = ? FOR UPDATE")
                .param(id).query(InboundEventRepository::mapStored).optional();
    }

    /**
     * Righe {@code UNMATCHED} ricevute da {@code since} il cui subject è {@code externalSubject} (confronto esatto) o
     * {@code emailSubject} (senza maiuscole; {@code email:} in minuscolo come lo riconosce la pipeline). Le più
     * vecchie per prime, al massimo {@code limit}.
     */
    public java.util.List<String> findUnmatchedIds(String externalSubject, String emailSubject, Instant since, int limit) {
        return jdbc.sql("""
                        SELECT id FROM inbound_event
                        WHERE status = 'UNMATCHED' AND received_at >= ?
                          AND (subject = ? OR (subject LIKE 'email:%' AND lower(subject) = ?))
                        ORDER BY received_at ASC LIMIT ?
                        """)
                .params(java.sql.Timestamp.from(since), externalSubject, emailSubject, limit)
                .query(String.class).list();
    }

    /**
     * Rivalutazione riuscita: la riga {@code REJECTED}/{@code UNMATCHED} diventa {@code ACCEPTED} con il membro, il
     * payload arricchito (quello pubblicato) e la correlazione. Il subject originale resta per provenienza.
     * {@code false} se la riga non era più in uno stato risolvibile (nessun aggiornamento). Una violazione
     * dell'indice unico parziale (un ACCEPTED con la stessa fonte+id nel frattempo) fa fallire la transazione:
     * outbox compresa, quindi nulla viene pubblicato.
     */
    public boolean markAccepted(String id, String memberId, String payloadJson, String correlationId,
                                String resolution, String resolvedBy, Instant resolvedAt) {
        return jdbc.sql("""
                        UPDATE inbound_event SET status = 'ACCEPTED', reject_code = NULL, reject_detail = NULL,
                          member_id = ?, payload = cast(? AS jsonb), correlation_id = ?,
                          resolution = ?, resolved_by = ?, resolved_at = ?
                        WHERE id = ? AND status IN ('REJECTED', 'UNMATCHED')
                        """)
                .params(memberId, payloadJson, correlationId, resolution, resolvedBy,
                        java.sql.Timestamp.from(resolvedAt), id)
                .update() == 1;
    }

    /** Rivalutazione non riuscita: si aggiorna l'esito (stato, codice, dettaglio, membro) con quello nuovo. */
    public boolean updateOutcome(String id, InboundStatus status, RejectCode rejectCode, String rejectDetail, String memberId) {
        return jdbc.sql("""
                        UPDATE inbound_event SET status = ?, reject_code = ?, reject_detail = ?, member_id = ?
                        WHERE id = ? AND status IN ('REJECTED', 'UNMATCHED')
                        """)
                .params(status.name(), rejectCode == null ? null : rejectCode.name(), rejectDetail, memberId, id)
                .update() == 1;
    }

    private static InboundRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp("received_at");
        java.sql.Timestamp resolved = rs.getTimestamp("resolved_at");
        return new InboundRow(rs.getString("id"), rs.getString("event_id"), rs.getString("source_code"),
                rs.getString("type_code"), rs.getString("subject"), rs.getString("member_id"),
                ts == null ? null : ts.toInstant(), rs.getString("status"), rs.getString("reject_code"),
                rs.getString("reject_detail"), rs.getString("correlation_id"), rs.getString("origin"),
                rs.getString("resolution"), rs.getString("resolved_by"), resolved == null ? null : resolved.toInstant());
    }

    private static StoredInbound mapStored(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Timestamp time = rs.getTimestamp("event_time");
        return new StoredInbound(mapRow(rs, n), time == null ? null : time.toInstant(), rs.getString("payload"));
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

    /**
     * Riga dello storico demo (docs/servizi/ingestion-service.md §6) con l'istante di ricezione del seed: nessuna dedup,
     * nessuna pubblicazione. Solo {@code DemoSeeder}.
     */
    public void insertHistory(String id, String eventId, String sourceCode, String typeCode, String subject,
                              String memberId, Instant eventTime, Instant receivedAt, InboundStatus status,
                              RejectCode rejectCode, String rejectDetail, String payloadJson, String correlationId,
                              String origin) {
        jdbc.sql("""
                        INSERT INTO inbound_event
                          (id, event_id, source_code, type_code, subject, member_id, event_time, received_at,
                           status, reject_code, reject_detail, payload, correlation_id, origin)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?)
                        """)
                .params(id, eventId, sourceCode, typeCode, subject, memberId, java.sql.Timestamp.from(eventTime),
                        java.sql.Timestamp.from(receivedAt), status.name(),
                        rejectCode == null ? null : rejectCode.name(), rejectDetail, payloadJson, correlationId, origin)
                .update();
    }

    /** Elimina le righe dello storico demo (id evento con il prefisso del seed) prima di ricaricarle. */
    public int deleteHistory(String eventIdPrefix) {
        return jdbc.sql("DELETE FROM inbound_event WHERE event_id LIKE ?").param(eventIdPrefix + "%").update();
    }

    /** Reset demo (docs/06 §10): svuota il monitor ingressi, storico e ingressi reali, prima di ricaricare il seed. */
    public int deleteAll() {
        return jdbc.sql("DELETE FROM inbound_event").update();
    }

    /**
     * Envelope canonico (già arricchito: {@code lhcorrelationid}, {@code lhhop}, subject normalizzato) dell'azione
     * accettata con questa (fonte, id): serve a <em>riprocessa</em> DLQ (docs/servizi/insight-service.md §5).
     */
    public java.util.Optional<String> findAcceptedPayload(String sourceCode, String eventId) {
        return jdbc.sql("""
                        SELECT payload::text FROM inbound_event
                        WHERE source_code = ? AND event_id = ? AND status = 'ACCEPTED'
                        ORDER BY received_at DESC LIMIT 1
                        """)
                .params(sourceCode, eventId).query(String.class).optional();
    }
}
