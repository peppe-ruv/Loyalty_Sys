package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.Webhook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Webhook (docs/servizi/engagement-service.md §2). Il segreto non esce mai dalle letture "di vista": lo legge solo
 * chi firma ({@link #findEnabledFor}, {@link #secretOf}). I tipi di fatto sono passati come testo separato da virgole
 * ({@code string_to_array}): i tipi non contengono virgole.
 */
@Repository
public class WebhookRepository {

    /** Destinatario di un fatto: quanto serve per creare la consegna firmata. */
    public record Target(String id, String secret) {
    }

    private static final String VIEW = """
            SELECT w.id, w.code, w.name, w.url, w.fact_types, w.enabled, w.version, w.created_at, w.created_by,
                   w.updated_at, w.updated_by,
                   s.total, s.ok, s.failed, s.gave_up, s.pending, s.last_at,
                   (SELECT d.status FROM webhook_delivery d WHERE d.webhook_id = w.id
                     ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS last_status
            FROM webhook w
            LEFT JOIN LATERAL (
              SELECT count(*) AS total,
                     count(*) FILTER (WHERE status = 'OK') AS ok,
                     count(*) FILTER (WHERE status = 'FAILED') AS failed,
                     count(*) FILTER (WHERE status = 'GAVE_UP') AS gave_up,
                     count(*) FILTER (WHERE status = 'PENDING') AS pending,
                     max(created_at) AS last_at
              FROM webhook_delivery WHERE webhook_id = w.id) s ON true
            """;

    private final JdbcClient jdbc;

    public WebhookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Webhook> findAll() {
        return jdbc.sql(VIEW + " ORDER BY w.created_at, w.code").query(WebhookRepository::map).list();
    }

    public Optional<Webhook> find(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql(VIEW + " WHERE w.id = ? OR w.code = ?")
                .params(idOrCode.trim(), idOrCode.trim().toUpperCase()).query(WebhookRepository::map).optional();
    }

    /** Webhook attivi abbonati a un tipo di fatto (forma breve), in ordine deterministico. */
    public List<Target> findEnabledFor(String factType) {
        return jdbc.sql("SELECT id, secret FROM webhook WHERE enabled AND ? = ANY(fact_types) ORDER BY code")
                .param(factType).query((rs, n) -> new Target(rs.getString("id"), rs.getString("secret"))).list();
    }

    public Optional<String> secretOf(String id) {
        return jdbc.sql("SELECT secret FROM webhook WHERE id = ?").param(id).query(String.class).optional();
    }

    public boolean codeTaken(String code) {
        return jdbc.sql("SELECT count(*) FROM webhook WHERE code = ?").param(code).query(Long.class).single() > 0;
    }

    public void insert(Webhook w, String secret, String actor) {
        jdbc.sql("""
                        INSERT INTO webhook (id, code, name, url, secret, fact_types, enabled, created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, string_to_array(?, ','), ?, ?, ?)
                        """)
                .params(w.id(), w.code(), w.name(), w.url(), secret, String.join(",", w.factTypes()), w.enabled(), actor, actor)
                .update();
    }

    /** Aggiorna se la versione è ancora {@code expectedVersion}; {@code false} = modificato nel frattempo (409). */
    public boolean update(Webhook w, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE webhook SET name = ?, url = ?, fact_types = string_to_array(?, ','), enabled = ?,
                          version = version + 1, updated_at = now(), updated_by = ?
                        WHERE id = ? AND version = ?
                        """)
                .params(w.name(), w.url(), String.join(",", w.factTypes()), w.enabled(), actor, w.id(), expectedVersion)
                .update() == 1;
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM webhook WHERE id = ?").param(id).update() == 1;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM webhook").update();
    }

    private static Webhook map(ResultSet rs, int n) throws SQLException {
        Timestamp last = rs.getTimestamp("last_at");
        Webhook.Stats stats = new Webhook.Stats(rs.getLong("total"), rs.getLong("ok"), rs.getLong("failed"),
                rs.getLong("gave_up"), rs.getLong("pending"), last == null ? null : last.toInstant(), rs.getString("last_status"));
        return new Webhook(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("url"),
                toList(rs.getArray("fact_types")), rs.getBoolean("enabled"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"), stats, null);
    }

    private static List<String> toList(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
