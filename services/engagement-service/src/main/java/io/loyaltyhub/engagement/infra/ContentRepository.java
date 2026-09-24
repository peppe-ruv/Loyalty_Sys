package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.ContentItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Contenuti del CMS (docs/servizi/engagement-service.md §2, tabella {@code content_item}). */
@Repository
public class ContentRepository {

    private static final String COLUMNS = """
            id, code, kind, placement, title, body, image_url, cta_label, cta_target, link_type, link_code,
            audience::text AS audience, start_at, end_at, priority, frequency, dismissible, style::text AS style, status,
            version, updated_at""";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ContentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Elenco della gestione con filtri opzionali; {@code q} cerca in titolo e codice. */
    public List<ContentItem> findAll(String kind, String placement, String status, String q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM content_item WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (present(kind)) {
            sql.append(" AND kind = ?");
            params.add(kind.trim().toUpperCase());
        }
        if (present(placement)) {
            sql.append(" AND placement = ?");
            params.add(placement.trim().toUpperCase());
        }
        if (present(status)) {
            sql.append(" AND status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (present(q)) {
            sql.append(" AND (title ILIKE ? OR code ILIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY CASE status WHEN 'LIVE' THEN 0 WHEN 'PAUSED' THEN 1 WHEN 'DRAFT' THEN 2 ELSE 3 END, priority DESC, code");
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    /** Candidati di un posizionamento, qualunque stato: la selezione decide e spiega le esclusioni. */
    public List<ContentItem> findByPlacement(String placement) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM content_item WHERE placement = ? AND kind <> 'POPUP' AND status <> 'ARCHIVED'")
                .param(placement).query(this::map).list();
    }

    /** Pop-up candidati, qualunque stato (esclusi gli archiviati): la selezione decide e spiega le esclusioni. */
    public List<ContentItem> findPopups() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM content_item WHERE kind = 'POPUP' AND status <> 'ARCHIVED'")
                .query(this::map).list();
    }

    public Optional<ContentItem> find(String idOrCode) {
        if (idOrCode == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM content_item WHERE id = ? OR code = ?")
                .params(idOrCode.trim(), idOrCode.trim().toUpperCase()).query(this::map).optional();
    }

    public boolean existsCode(String code) {
        return jdbc.sql("SELECT count(*) FROM content_item WHERE code = ?").param(code).query(Long.class).single() > 0;
    }

    public void insert(ContentItem c, String actor) {
        jdbc.sql("""
                        INSERT INTO content_item (id, code, kind, placement, title, body, image_url, cta_label, cta_target,
                          link_type, link_code, audience, start_at, end_at, priority, frequency, dismissible, style, status,
                          created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """)
                .params(c.id(), c.code(), c.kind(), c.placement(), c.title(), c.body(), c.imageUrl(), c.ctaLabel(),
                        c.ctaTarget(), c.linkType(), c.linkCode(), json(c.audience()), ts(c.startAt()), ts(c.endAt()),
                        c.priority(), c.frequency(), c.dismissible(), json(c.style()), c.status(), actor, actor)
                .update();
    }

    /** Aggiorna i campi modificabili se la versione è ancora {@code expectedVersion}; {@code false} = conflitto. */
    public boolean update(ContentItem c, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE content_item SET placement = ?, title = ?, body = ?, image_url = ?, cta_label = ?, cta_target = ?,
                          link_type = ?, link_code = ?, audience = ?::jsonb, start_at = ?, end_at = ?, priority = ?,
                          frequency = ?, dismissible = ?, style = ?::jsonb, version = version + 1, updated_at = now(),
                          updated_by = ?
                        WHERE id = ? AND version = ?
                        """)
                .params(c.placement(), c.title(), c.body(), c.imageUrl(), c.ctaLabel(), c.ctaTarget(), c.linkType(),
                        c.linkCode(), json(c.audience()), ts(c.startAt()), ts(c.endAt()), c.priority(), c.frequency(),
                        c.dismissible(), json(c.style()), actor, c.id(), expectedVersion)
                .update() == 1;
    }

    public void updateStatus(String id, String status, String actor) {
        jdbc.sql("UPDATE content_item SET status = ?, version = version + 1, updated_at = now(), updated_by = ? WHERE id = ?")
                .params(status, actor, id).update();
    }

    /** Contenuti {@code LIVE}/{@code PAUSED} con {@code end_at} passato: i candidati del job di fine (§5). */
    public List<ContentItem> expiredBy(Instant asOf) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM content_item WHERE status IN ('LIVE', 'PAUSED') AND end_at <= ?")
                .param(ts(asOf)).query(this::map).list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM content_item").update();
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "{}" : mapper.writeValueAsString(node);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    private ContentItem map(ResultSet rs, int n) throws SQLException {
        Timestamp start = rs.getTimestamp("start_at");
        Timestamp end = rs.getTimestamp("end_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ContentItem(rs.getString("id"), rs.getString("code"), rs.getString("kind"), rs.getString("placement"),
                rs.getString("title"), rs.getString("body"), rs.getString("image_url"), rs.getString("cta_label"),
                rs.getString("cta_target"), rs.getString("link_type"), rs.getString("link_code"),
                mapper.readTree(rs.getString("audience")), start == null ? null : start.toInstant(),
                end == null ? null : end.toInstant(), rs.getInt("priority"), rs.getString("frequency"),
                rs.getBoolean("dismissible"), mapper.readTree(rs.getString("style")), rs.getString("status"),
                rs.getLong("version"), updated == null ? null : updated.toInstant());
    }
}
