package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlOrder;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.insight.domain.StoredEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Event store (docs/servizi/insight-service.md §2, §5). Inserimento idempotente su {@code event_id}
 * ({@code ON CONFLICT DO NOTHING}); la retention taglia per età e per numero di righe.
 */
@Repository
public class EventStoreRepository {

    /** Colonne ammesse nei filtri e nell'ordinamento di ricerca e tracciati (regola 19, ADR-042). */
    enum EventColumn implements SqlColumn {
        TOPIC("topic"), FAMILY("family"), SHORT_TYPE("short_type"), MEMBER_ID("member_id"),
        CORRELATION_ID("correlation_id"), SOURCE("source"), RECEIVED_AT("received_at"),
        /** Resa testuale del payload, per la ricerca libera {@code q}. */
        PAYLOAD_TEXT("payload::text");

        private final String sql;

        EventColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    /**
     * Testo SQL costante della ricerca (regola 19, docs/18 §3.10 punto 4): vi si accodano solo {@link SqlWhere#sql()},
     * l'ordinamento costante {@link #SEARCH_ORDER} e la pagina legata {@link #PAGE}.
     */
    static final String SEARCH_SELECT = "SELECT * FROM event_store";

    /** Come {@link #SEARCH_SELECT}, per il totale: vi si accoda solo {@link SqlWhere#sql()}. */
    static final String COUNT_SELECT = "SELECT count(*) FROM event_store";

    /** Dal più recente, come prima del builder. */
    static final String SEARCH_ORDER = SqlOrder.desc(EventColumn.RECEIVED_AT).sql();

    static final String PAGE = " LIMIT :limit OFFSET :offset";

    /**
     * Testo SQL costante dei tracciati: il {@code WHERE} costante esclude gli eventi senza tracciato e vi si accodano
     * solo {@link SqlWhere#andSql()} e {@link #CORRELATIONS_PAGE}.
     */
    static final String CORRELATIONS_SELECT = "SELECT correlation_id FROM event_store WHERE correlation_id IS NOT NULL";

    /** Come {@link #CORRELATIONS_SELECT}, per il totale: vi si accoda solo {@link SqlWhere#andSql()}. */
    static final String CORRELATIONS_COUNT =
            "SELECT count(DISTINCT correlation_id) FROM event_store WHERE correlation_id IS NOT NULL";

    /**
     * Un tracciato per riga, dal più recentemente osservato. {@code max(received_at)} è un aggregato, non una colonna
     * dell'allowlist: resta testo costante.
     */
    static final String CORRELATIONS_PAGE =
            " GROUP BY correlation_id ORDER BY max(received_at) DESC LIMIT :limit OFFSET :offset";

    private final JdbcClient jdbc;

    public EventStoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserisce l'evento; restituisce {@code true} se era nuovo (un duplicato non conta due volte). */
    public boolean insert(StoredEvent e) {
        int rows = jdbc.sql("""
                        INSERT INTO event_store
                          (event_id, topic, family, type, short_type, source, member_id, correlation_id,
                           causation_id, hop, actor, error_code, event_time, kafka_partition, kafka_offset, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb))
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .params(e.eventId(), e.topic(), e.family(), e.type(), e.shortType(), e.source(), e.memberId(),
                        e.correlationId(), e.causationId(), e.hop(), e.actor(), e.errorCode(),
                        e.eventTime() == null ? null : Timestamp.from(e.eventTime()),
                        e.partition(), e.offset(), e.payloadJson() == null ? "{}" : e.payloadJson())
                .update();
        return rows > 0;
    }

    public Optional<StoredEvent> findById(String eventId) {
        return jdbc.sql("SELECT * FROM event_store WHERE event_id = ?").param(eventId)
                .query(EventStoreRepository::map).optional();
    }

    /** Ricerca con filtri (docs/servizi/insight-service.md §3, {@code GET /v1/events}), dal più recente, a pagine. */
    public List<StoredEvent> search(String topic, String family, String type, String memberId,
                                    String correlationId, String source, Instant from, Instant to,
                                    String q, int limit, int offset) {
        SqlWhere where = filters(topic, family, type, memberId, correlationId, source, from, to, q);
        return where.bind(jdbc.sql(SEARCH_SELECT + where.sql() + SEARCH_ORDER + PAGE))
                .param("limit", limit)
                .param("offset", offset)
                .query(EventStoreRepository::map).list();
    }

    /** Totale degli eventi che passano i filtri di {@link #search} ({@code page.totalItems}, docs/06 §2). */
    public long count(String topic, String family, String type, String memberId, String correlationId,
                      String source, Instant from, Instant to, String q) {
        SqlWhere where = filters(topic, family, type, memberId, correlationId, source, from, to, q);
        return where.bind(jdbc.sql(COUNT_SELECT + where.sql())).query(Long.class).single();
    }

    /**
     * Filtri facoltativi della ricerca, ignorati se assenti o vuoti; ogni testo è ripulito una volta sola (spazi
     * esterni tolti, la famiglia anche in maiuscolo). {@code q} è un testo letterale cercato nel payload senza
     * distinguere maiuscole ({@code %}, {@code _} e {@code \} non sono caratteri jolly); l'intervallo
     * {@code [from, to]} è su {@code received_at}, estremi inclusi.
     */
    static SqlWhere filters(String topic, String family, String type, String memberId, String correlationId,
                            String source, Instant from, Instant to, String q) {
        String cleanFamily = clean(family);
        String text = clean(q);
        return new SqlWhere()
                .eqIfPresent(EventColumn.TOPIC, clean(topic))
                .eqIfPresent(EventColumn.FAMILY, cleanFamily == null ? null : cleanFamily.toUpperCase())
                .eqIfPresent(EventColumn.SHORT_TYPE, clean(type))
                .eqIfPresent(EventColumn.MEMBER_ID, clean(memberId))
                .eqIfPresent(EventColumn.CORRELATION_ID, clean(correlationId))
                .eqIfPresent(EventColumn.SOURCE, clean(source))
                .when(from != null, w -> w.gte(EventColumn.RECEIVED_AT, Timestamp.from(from)))
                .when(to != null, w -> w.lte(EventColumn.RECEIVED_AT, Timestamp.from(to)))
                .when(text != null, w -> w.ilike(EventColumn.PAYLOAD_TEXT, text, SqlWhere.Match.CONTAINS));
    }

    /** Valore senza spazi esterni, oppure {@code null} (nessun filtro) se assente o vuoto. */
    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Tutti gli eventi di un tracciato, in ordine di osservazione (per costruire l'albero). */
    public List<StoredEvent> byCorrelation(String correlationId) {
        return jdbc.sql("SELECT * FROM event_store WHERE correlation_id = ? ORDER BY received_at ASC")
                .param(correlationId).query(EventStoreRepository::map).list();
    }

    /** Gli ultimi correlationId osservati (uno per tracciato), opzionalmente per membro e intervallo, a pagine. */
    public List<String> recentCorrelationIds(String memberId, Instant from, Instant to, int limit, int offset) {
        SqlWhere where = correlationFilters(memberId, from, to);
        return where.bind(jdbc.sql(CORRELATIONS_SELECT + where.andSql() + CORRELATIONS_PAGE))
                .param("limit", limit)
                .param("offset", offset)
                .query(String.class).list();
    }

    /** Numero di tracciati distinti che passano i filtri di {@link #recentCorrelationIds}. */
    public long countCorrelationIds(String memberId, Instant from, Instant to) {
        SqlWhere where = correlationFilters(memberId, from, to);
        return where.bind(jdbc.sql(CORRELATIONS_COUNT + where.andSql())).query(Long.class).single();
    }

    /**
     * Eventi arrivati per topic nell'ultimo intervallo (volumi 1 h / 24 h dello stato pipeline, insight §3). La finestra
     * è calcolata dal database, come {@code received_at}.
     */
    public Map<String, Long> countByTopicWithin(java.time.Duration window) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("""
                        SELECT topic, count(*) AS n FROM event_store
                        WHERE received_at >= now() - make_interval(secs => ?) GROUP BY topic
                        """)
                .param((double) window.toSeconds())
                .query((rs, n) -> out.put(rs.getString("topic"), rs.getLong("n")))
                .list();
        return out;
    }

    /** Filtri facoltativi dei tracciati: membro ripulito una volta sola, intervallo su {@code received_at} incluso. */
    static SqlWhere correlationFilters(String memberId, Instant from, Instant to) {
        return new SqlWhere()
                .eqIfPresent(EventColumn.MEMBER_ID, clean(memberId))
                .when(from != null, w -> w.gte(EventColumn.RECEIVED_AT, Timestamp.from(from)))
                .when(to != null, w -> w.lte(EventColumn.RECEIVED_AT, Timestamp.from(to)));
    }

    /** Retention per età (docs §5): elimina gli eventi più vecchi di N giorni. */
    public int deleteOlderThan(int days) {
        return jdbc.sql("DELETE FROM event_store WHERE received_at < now() - make_interval(days => ?)")
                .param(days).update();
    }

    /** Retention per numero (docs §5): tiene le {@code maxRows} righe più recenti. */
    public int trimToMaxRows(int maxRows) {
        return jdbc.sql("""
                        DELETE FROM event_store
                        WHERE event_id IN (
                          SELECT event_id FROM event_store
                          ORDER BY received_at DESC
                          OFFSET ?
                        )
                        """)
                .param(maxRows).update();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM event_store").query(Long.class).single();
    }

    /** Membri distinti che compaiono con un dato tipo evento (es. {@code member.registered}). */
    public long distinctMembers(String shortType) {
        return jdbc.sql("""
                        SELECT count(DISTINCT member_id) FROM event_store
                        WHERE short_type = ? AND member_id IS NOT NULL
                        """)
                .param(shortType).query(Long.class).single();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM event_store").update();
    }

    private static StoredEvent map(ResultSet rs, int n) throws SQLException {
        int hopValue = rs.getInt("hop");
        Integer hop = rs.wasNull() ? null : hopValue; // wasNull() vale sull'ultima colonna letta: subito dopo getInt
        Timestamp eventTime = rs.getTimestamp("event_time");
        Timestamp receivedAt = rs.getTimestamp("received_at");
        return new StoredEvent(
                rs.getString("event_id"), rs.getString("topic"), rs.getString("family"), rs.getString("type"),
                rs.getString("short_type"), rs.getString("source"), rs.getString("member_id"),
                rs.getString("correlation_id"), rs.getString("causation_id"), hop,
                rs.getString("actor"), rs.getString("error_code"),
                eventTime == null ? null : eventTime.toInstant(),
                receivedAt == null ? Instant.EPOCH : receivedAt.toInstant(),
                rs.getInt("kafka_partition"), rs.getLong("kafka_offset"), rs.getString("payload"));
    }
}
