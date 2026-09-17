package io.loyaltyhub.readmodel.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.loyaltyhub.readmodel.domain.ContextProjector;
import io.loyaltyhub.readmodel.domain.CustomerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Persistenza del contesto: una riga JSONB per membro con lock di riga per aggiornamenti atomici dai consumer.
 *
 * <p>Le letture passano da una cache <b>Redis</b> condivisa fra le repliche (chiave {@code readmodel:ctx:<membro>},
 * TTL breve, scrittura in write-through a ogni proiezione). Una cache in memoria per processo — com'era prima — non
 * vedeva le scritture delle altre repliche, non scadeva mai e cresceva quanto i membri: il motore decisionale poteva
 * decidere su un contesto vecchio di ore. Se Redis non risponde si legge dal database: la cache è un accorgimento di
 * prestazione, non una dipendenza per rispondere.
 */
@Component
public class ContextStore {
    private static final Logger log = LoggerFactory.getLogger(ContextStore.class);
    private static final String PREFIX = "readmodel:ctx:";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public ContextStore(JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redis,
                        @Value("${readmodel.cache.ttl-seconds:300}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.redis = redis.getIfAvailable();
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    @Transactional
    public CustomerContext update(String memberId, UnaryOperator<CustomerContext> fn) {
        String s = jdbc.query("SELECT context FROM readmodel.customer_context WHERE member_id = ? FOR UPDATE", rs -> rs.next() ? rs.getString(1) : null, memberId);
        CustomerContext cur = s == null ? ContextProjector.empty(memberId) : read(s);
        CustomerContext next = fn.apply(cur);
        String document;
        try {
            document = json.writeValueAsString(next);
            jdbc.update("INSERT INTO readmodel.customer_context(member_id, context, updated_at) VALUES (?,?::jsonb,?) ON CONFLICT (member_id) DO UPDATE SET context = EXCLUDED.context, updated_at = EXCLUDED.updated_at",
                    memberId, document, Timestamp.from(next.updatedAt()));
        } catch (Exception e) { throw new IllegalStateException(e); }
        // vista compatta per il sito (RF-50)
        var premio = next.loyalty().wallets().get("PREMIO");
        jdbc.update("INSERT INTO readmodel.member_summary(member_id, reward_available, status_year, tier, updated_at) VALUES (?,?,?,?,now()) ON CONFLICT (member_id) DO UPDATE SET reward_available = EXCLUDED.reward_available, status_year = EXCLUDED.status_year, tier = EXCLUDED.tier, updated_at = now()",
                memberId, premio == null ? 0 : premio.active(), next.loyalty().statusPointsYear(), next.loyalty().tier());
        cache(memberId, document);
        return next;
    }

    public CustomerContext get(String memberId) {
        String cached = cached(memberId);
        CustomerContext c;
        if (cached != null) {
            c = read(cached);
        } else {
            String s = jdbc.query("SELECT context FROM readmodel.customer_context WHERE member_id = ?", rs -> rs.next() ? rs.getString(1) : null, memberId);
            c = s == null ? ContextProjector.empty(memberId) : read(s);
            if (s != null) cache(memberId, s);
        }
        return ContextProjector.refreshed(c, Instant.now());
    }

    /** Paginazione keyset per i ricalcoli massivi (segmenti, automazioni): id > after, ordinati. */
    public List<CustomerContext> page(String after, int size) {
        return jdbc.query("SELECT context FROM readmodel.customer_context WHERE member_id > ? ORDER BY member_id LIMIT ?", (rs, i) -> read(rs.getString(1)), after == null ? "" : after, size);
    }

    /**
     * Ricalcolo delle finestre mobili su tutti i contesti (RF-125): le finestre a 7, 30, 90 e 365 giorni scadono anche
     * quando non arriva nessun evento. Scorre in keyset, riscrive solo ciò che cambia davvero e invalida la cache.
     *
     * @return quanti contesti sono stati riscritti
     */
    public int recomputeWindows(int pageSize) {
        Instant now = Instant.now();
        String after = "";
        int scritti = 0;
        while (true) {
            List<CustomerContext> page = page(after, pageSize);
            if (page.isEmpty()) break;
            for (CustomerContext c : page) {
                CustomerContext ricalcolato = ContextProjector.recomputeWindows(c, now);
                if (!cambiato(c, ricalcolato)) continue;
                try {
                    String document = json.writeValueAsString(ricalcolato);
                    jdbc.update("UPDATE readmodel.customer_context SET context = ?::jsonb WHERE member_id = ?", document, c.memberId());
                    cache(c.memberId(), document);
                    scritti++;
                } catch (Exception e) {
                    log.warn("ricalcolo delle finestre non riuscito per un contesto: {}", e.getMessage());
                }
            }
            after = page.get(page.size() - 1).memberId();
        }
        if (scritti > 0) log.info("ricalcolo notturno delle finestre: {} contesti aggiornati", scritti);
        return scritti;
    }

    /** Solo le finestre possono cambiare: confrontarle evita una riscrittura inutile per ogni membro fermo. */
    private static boolean cambiato(CustomerContext prima, CustomerContext dopo) {
        return !prima.behaviour().rfm().equals(dopo.behaviour().rfm())
                || !prima.behaviour().actionCounts30d().equals(dopo.behaviour().actionCounts30d())
                || !prima.engagement().contacts7dByChannel().equals(dopo.engagement().contacts7dByChannel());
    }

    private void cache(String memberId, String document) {
        if (redis == null) return;
        try { redis.opsForValue().set(PREFIX + memberId, document, ttl); }
        catch (RuntimeException e) { log.debug("cache del contesto non scrivibile: {}", e.getMessage()); }
    }

    private String cached(String memberId) {
        if (redis == null) return null;
        try { return redis.opsForValue().get(PREFIX + memberId); }
        catch (RuntimeException e) { log.debug("cache del contesto non leggibile: {}", e.getMessage()); return null; }
    }

    private CustomerContext read(String s) { try { return json.readValue(s, CustomerContext.class); } catch (Exception e) { throw new IllegalStateException(e); } }
}
