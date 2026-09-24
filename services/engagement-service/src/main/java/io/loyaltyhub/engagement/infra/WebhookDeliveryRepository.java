package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.WebhookDelivery;
import io.loyaltyhub.engagement.domain.WebhookRetry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Consegne dei webhook (docs/servizi/engagement-service.md §2, §5). L'invio avviene fuori transazione: una consegna si
 * "prende in carico" impostando {@code claimed_until} (lease) con un UPDATE breve ({@code SKIP LOCKED} per lo
 * scheduler), poi si chiama l'endpoint e infine {@link #recordAttempt} registra l'esito e libera la lease.
 */
@Repository
public class WebhookDeliveryRepository {

    /** Consegna presa in carico: quanto serve per la chiamata HTTP. */
    public record Claim(String id, String eventId, String payload, String signature, int attempt, String url) {
    }

    /** Esito di un tentativo già deciso dalla politica ({@link WebhookRetry}). */
    public record Attempt(int attempt, String status, Integer httpStatus, String responseExcerpt, String error,
                          Integer durationMs, Instant nextAttemptAt, Instant lastAttemptAt) {
    }

    private static final String COLUMNS = """
            id, webhook_id, event_id, fact_type, member_id, test, payload, signature, attempt, status, http_status,
            response_excerpt, error, duration_ms, next_attempt_at, last_attempt_at, created_at""";

    private final JdbcClient jdbc;

    public WebhookDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Nuova consegna {@code PENDING}; {@code false} se esiste già per (webhook, evento): rielaborazione idempotente. */
    public boolean insertIfAbsent(String id, String webhookId, String eventId, String factType, String memberId, boolean test,
                                  String payload, String signature, Instant createdAt) {
        return jdbc.sql("""
                        INSERT INTO webhook_delivery (id, webhook_id, event_id, fact_type, member_id, test, payload, signature,
                                                      status, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                        ON CONFLICT (webhook_id, event_id) DO NOTHING
                        """)
                .params(id, webhookId, eventId, factType, memberId, test, payload, signature, ts(createdAt), ts(createdAt))
                .update() == 1;
    }

    /** Consegna storica del seed, con l'esito già scritto. */
    public void insertSeed(WebhookDelivery d) {
        jdbc.sql("""
                        INSERT INTO webhook_delivery (id, webhook_id, event_id, fact_type, member_id, test, payload, signature,
                                                      attempt, status, http_status, response_excerpt, error, duration_ms,
                                                      next_attempt_at, last_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(d.id(), d.webhookId(), d.eventId(), d.factType(), d.memberId(), d.test(), d.payload(), d.signature(),
                        d.attempt(), d.status(), d.httpStatus(), d.responseExcerpt(), d.error(), d.durationMs(),
                        ts(d.nextAttemptAt()), ts(d.lastAttemptAt()), ts(d.createdAt()))
                .update();
    }

    public Optional<WebhookDelivery> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_delivery WHERE id = ?").param(id)
                .query(WebhookDeliveryRepository::map).optional();
    }

    public List<WebhookDelivery> page(String webhookId, String status, int page, int size) {
        List<Object> params = new ArrayList<>();
        String where = where(webhookId, status, params);
        params.add(size);
        params.add((long) page * size);
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_delivery" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params).query(WebhookDeliveryRepository::map).list();
    }

    public long count(String webhookId, String status) {
        List<Object> params = new ArrayList<>();
        String where = where(webhookId, status, params);
        return jdbc.sql("SELECT count(*) FROM webhook_delivery" + where).params(params).query(Long.class).single();
    }

    /**
     * Prende in carico fino a {@code limit} consegne dovute a {@code asOf} ({@code PENDING}/{@code FAILED} con
     * {@code next_attempt_at <= asOf}) non già in carico a {@code now}; la lease scade a {@code leaseUntil}.
     */
    public List<Claim> claimDue(Instant asOf, Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE webhook_delivery d SET claimed_until = ?
                        FROM webhook w
                        WHERE w.id = d.webhook_id AND d.id IN (
                          SELECT id FROM webhook_delivery
                          WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?
                            AND (claimed_until IS NULL OR claimed_until < ?)
                          ORDER BY next_attempt_at, id
                          LIMIT ?
                          FOR UPDATE SKIP LOCKED)
                        RETURNING d.id, d.event_id, d.payload, d.signature, d.attempt, w.url
                        """)
                .params(ts(leaseUntil), ts(asOf), ts(now), limit)
                .query((rs, n) -> new Claim(rs.getString("id"), rs.getString("event_id"), rs.getString("payload"),
                        rs.getString("signature"), rs.getInt("attempt"), rs.getString("url")))
                .list()
                .stream()
                .sorted(java.util.Comparator.comparing(Claim::id))
                .toList();
    }

    /** Presa in carico di una consegna precisa (prova, *Riprova*) se è in uno degli stati ammessi e libera. */
    public Optional<Claim> claim(String id, List<String> statuses, Instant now, Instant leaseUntil) {
        return jdbc.sql("""
                        UPDATE webhook_delivery d SET claimed_until = ?
                        FROM webhook w
                        WHERE w.id = d.webhook_id AND d.id = ? AND d.status = ANY(string_to_array(?, ','))
                          AND (d.claimed_until IS NULL OR d.claimed_until < ?)
                        RETURNING d.id, d.event_id, d.payload, d.signature, d.attempt, w.url
                        """)
                .params(ts(leaseUntil), id, String.join(",", statuses), ts(now))
                .query((rs, n) -> new Claim(rs.getString("id"), rs.getString("event_id"), rs.getString("payload"),
                        rs.getString("signature"), rs.getInt("attempt"), rs.getString("url")))
                .optional();
    }

    /** Registra l'esito di un tentativo e libera la lease. */
    public void recordAttempt(String id, Attempt a) {
        jdbc.sql("""
                        UPDATE webhook_delivery SET attempt = ?, status = ?, http_status = ?, response_excerpt = ?, error = ?,
                          duration_ms = ?, next_attempt_at = ?, last_attempt_at = ?, claimed_until = NULL
                        WHERE id = ?
                        """)
                .params(a.attempt(), a.status(), a.httpStatus(), a.responseExcerpt(), a.error(), a.durationMs(),
                        ts(a.nextAttemptAt()), ts(a.lastAttemptAt()), id)
                .update();
    }

    /** Pulizia (docs/servizi/engagement-service.md §5): consegne più vecchie di {@code before}. */
    public int deleteOlderThan(Instant before) {
        return jdbc.sql("DELETE FROM webhook_delivery WHERE created_at < ?").param(ts(before)).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM webhook_delivery").update();
    }

    private static String where(String webhookId, String status, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (webhookId != null && !webhookId.isBlank()) {
            sql.append(" AND webhook_id = ?");
            params.add(webhookId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.trim().toUpperCase());
        }
        return sql.toString();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static WebhookDelivery map(ResultSet rs, int n) throws SQLException {
        return new WebhookDelivery(rs.getString("id"), rs.getString("webhook_id"), rs.getString("event_id"),
                rs.getString("fact_type"), rs.getString("member_id"), rs.getBoolean("test"), rs.getString("payload"),
                rs.getString("signature"), rs.getInt("attempt"), WebhookRetry.MAX_ATTEMPTS, rs.getString("status"),
                integer(rs, "http_status"), rs.getString("response_excerpt"), rs.getString("error"), integer(rs, "duration_ms"),
                instant(rs, "next_attempt_at"), instant(rs, "last_attempt_at"), instant(rs, "created_at"));
    }
}
