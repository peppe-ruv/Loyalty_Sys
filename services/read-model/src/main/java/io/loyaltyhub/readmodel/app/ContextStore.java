package io.loyaltyhub.readmodel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.loyaltyhub.readmodel.domain.ContextProjector;
import io.loyaltyhub.readmodel.domain.CustomerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Persistenza del contesto: una riga JSONB per membro con lock di riga per aggiornamenti atomici dai consumer;
 * cache in memoria a breve TTL per le letture del motore decisionale (in cluster: Redis, {@code spring.cache}).
 */
@Component
public class ContextStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final java.util.Map<String, CustomerContext> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public ContextStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public CustomerContext update(String memberId, UnaryOperator<CustomerContext> fn) {
        String s = jdbc.query("SELECT context FROM readmodel.customer_context WHERE member_id = ? FOR UPDATE", rs -> rs.next() ? rs.getString(1) : null, memberId);
        CustomerContext cur = s == null ? ContextProjector.empty(memberId) : read(s);
        CustomerContext next = fn.apply(cur);
        try {
            jdbc.update("INSERT INTO readmodel.customer_context(member_id, context, updated_at) VALUES (?,?::jsonb,?) ON CONFLICT (member_id) DO UPDATE SET context = EXCLUDED.context, updated_at = EXCLUDED.updated_at",
                    memberId, json.writeValueAsString(next), Timestamp.from(next.updatedAt()));
        } catch (Exception e) { throw new IllegalStateException(e); }
        // vista compatta per il sito (RF-50)
        var premio = next.loyalty().wallets().get("PREMIO");
        jdbc.update("INSERT INTO readmodel.member_summary(member_id, reward_available, status_year, tier, updated_at) VALUES (?,?,?,?,now()) ON CONFLICT (member_id) DO UPDATE SET reward_available = EXCLUDED.reward_available, status_year = EXCLUDED.status_year, tier = EXCLUDED.tier, updated_at = now()",
                memberId, premio == null ? 0 : premio.active(), next.loyalty().statusPointsYear(), next.loyalty().tier());
        cache.put(memberId, next);
        return next;
    }

    public CustomerContext get(String memberId) {
        CustomerContext c = cache.get(memberId);
        if (c == null) {
            String s = jdbc.query("SELECT context FROM readmodel.customer_context WHERE member_id = ?", rs -> rs.next() ? rs.getString(1) : null, memberId);
            c = s == null ? ContextProjector.empty(memberId) : read(s);
            cache.put(memberId, c);
        }
        return ContextProjector.refreshed(c, Instant.now());
    }

    /** Paginazione keyset per i ricalcoli massivi (segmenti, automazioni): id > after, ordinati. */
    public List<CustomerContext> page(String after, int size) {
        return jdbc.query("SELECT context FROM readmodel.customer_context WHERE member_id > ? ORDER BY member_id LIMIT ?", (rs, i) -> read(rs.getString(1)), after == null ? "" : after, size);
    }

    private CustomerContext read(String s) { try { return json.readValue(s, CustomerContext.class); } catch (Exception e) { throw new IllegalStateException(e); } }
}
