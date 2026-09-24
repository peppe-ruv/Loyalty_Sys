package io.loyaltyhub.engagement.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Viste dei pop-up per membro e giorno (docs/servizi/engagement-service.md §2, tabella {@code popup_view}). */
@Repository
public class PopupViewRepository {

    private final JdbcClient jdbc;

    public PopupViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ultimo giorno di vista per ogni pop-up visto dal membro (assente = mai visto). */
    public Map<String, LocalDate> lastSeenByContent(String memberId) {
        Map<String, LocalDate> out = new HashMap<>();
        jdbc.sql("SELECT content_id, max(view_date) AS last FROM popup_view WHERE member_id = ? GROUP BY content_id")
                .param(memberId)
                .query(rs -> {
                    out.put(rs.getString("content_id"), rs.getDate("last").toLocalDate());
                });
        return out;
    }

    /** Registra la vista del giorno (idempotente); {@code dismissed} segna anche la chiusura. */
    public void recordSeen(String contentId, String memberId, LocalDate day, Instant at, boolean dismissed) {
        Timestamp ts = Timestamp.from(at);
        jdbc.sql("""
                        INSERT INTO popup_view (content_id, member_id, view_date, seen_at, dismissed_at) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (content_id, member_id, view_date) DO UPDATE SET
                          dismissed_at = COALESCE(popup_view.dismissed_at, excluded.dismissed_at)
                        """)
                .params(contentId, memberId, Date.valueOf(day), ts, dismissed ? ts : null)
                .update();
    }

    /** Pulizia (docs §5): viste più vecchie di {@code before}. */
    public int deleteOlderThan(LocalDate before) {
        return jdbc.sql("DELETE FROM popup_view WHERE view_date < ?").param(Date.valueOf(before)).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM popup_view").update();
    }
}
