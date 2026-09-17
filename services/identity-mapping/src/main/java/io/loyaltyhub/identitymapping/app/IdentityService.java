package io.loyaltyhub.identitymapping.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.metrics.LoyaltyMetrics;
import io.loyaltyhub.identitymapping.domain.IdentityGraph;
import io.loyaltyhub.identitymapping.domain.IdentityGraph.Identifier;
import io.loyaltyhub.identitymapping.domain.IdentityGraph.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Risoluzione, collegamento, merge e unmerge delle identità (RF-136). Ogni nuovo collegamento produce l'azione
 * CUSTOMER_IDENTIFIED (le campagne possono premiare il primo riconoscimento su un canale); ogni merge produce
 * IDENTITY_V1 (MERGED/UNMERGED) per gli altri servizi e l'azione IDENTITY_MERGED; le unità del membro assorbito sono
 * trasferite sul ledger (porta {@link MergeEffects}), mai duplicate né perse.
 *
 * <p><b>Il merge non è una transazione.</b> Tocca tre domini — il grafo qui, il registro punti nel ledger,
 * l'anagrafica nel member-service — e due sono servizi remoti: una transazione locale non li annulla. Il merge è
 * quindi una piccola macchina a stati registrata su {@code merge_history}: ogni passo è idempotente e lascia traccia,
 * chi riprova con la stessa chiave riparte dal punto in cui si era fermato, e {@link #reconcile()} porta a termine da
 * solo quelli rimasti a metà. Prima di questo, un errore fra il trasferimento delle unità e la chiusura del membro
 * assorbito lasciava uno stato incoerente che nessuno recuperava.
 */
@Service
public class IdentityService {
    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private static final String SOURCE = "urn:loyaltyhub:identity-mapping";

    /** Passi del merge, in ordine; lo stato registrato dice qual è l'ultimo compiuto. */
    static final String PENDING = "PENDING", LINKS_MOVED = "LINKS_MOVED", UNITS_MOVED = "UNITS_MOVED",
            ALIASED = "ALIASED", COMPLETED = "COMPLETED", UNMERGING = "UNMERGING", UNMERGED = "UNMERGED";

    /** Un passo del merge non è riuscito: lo stato è registrato, il merge si riprende con la stessa chiave. */
    public static class MergeIncomplete extends RuntimeException {
        private final String mergeId;
        private final String status;
        public MergeIncomplete(String mergeId, String status, String message, Throwable cause) {
            super("merge " + mergeId + " fermo in " + status + ": " + message, cause);
            this.mergeId = mergeId; this.status = status;
        }
        public String mergeId() { return mergeId; }
        public String status() { return status; }
    }

    /**
     * Effetti del merge sugli altri domini. Tutte le operazioni sono idempotenti per chiave: il ledger deduplica per
     * {@code transferKey}, la chiusura e la riapertura del membro sono scritture di stato.
     */
    public interface MergeEffects {
        /** Trasferisce sul membro sopravvissuto le unità attive di quello assorbito; restituisce quanto ha spostato per wallet. */
        Map<String, Long> transferUnits(String fromMemberId, String intoMemberId, String mergeId);

        /**
         * Ritrasferisce al membro riattivato le unità che il merge aveva spostato. Il registro resta l'unica verità
         * sui saldi: se il membro sopravvissuto le ha spese, torna indietro solo ciò che c'è, e la differenza è un
         * ammanco dichiarato ({@link IdentityGraph.Merge#shortfall()}), mai un accredito creato dal nulla.
         */
        Map<String, Long> restoreUnits(String intoMemberId, String fromMemberId, Map<String, Long> units, String mergeId);

        void closeMember(String memberId, String reason);

        /** Riporta in vita il membro assorbito quando il merge viene annullato. */
        void reopenMember(String memberId, String reason);
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final MergeEffects effects;
    private final LoyaltyMetrics metrics;
    private final TransactionTemplate step;

    public IdentityService(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, MergeEffects effects,
                           LoyaltyMetrics metrics, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.kafka = kafka; this.effects = effects; this.metrics = metrics;
        this.step = new TransactionTemplate(transactions);
        this.step.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Risolve un insieme di identificatori a un membro: se uno (deterministico) è noto, gli altri vengono collegati;
     * se nessuno è noto e {@code createAs} è valorizzato, il membro viene creato con quell'id; identificatori che
     * puntano a membri diversi sono conflitti (candidati al merge), mai risolti in automatico.
     */
    @Transactional
    public IdentityGraph.Resolution resolve(List<Identifier> identifiers, String source, String createAs, String channel) {
        List<Identifier> ids = identifiers == null ? List.of() : identifiers.stream().filter(Identifier::valid).distinct().toList();
        Map<Identifier, String> known = new LinkedHashMap<>();
        for (Identifier i : ids) {
            String m = jdbc.query("SELECT member_id FROM identitymapping.identity_link WHERE kind = ? AND value = ?", rs -> rs.next() ? rs.getString(1) : null, i.kind(), i.value());
            if (m != null) known.put(i, canonical(m));
        }
        Set<String> members = new LinkedHashSet<>(known.values());
        String deterministic = known.entrySet().stream().filter(e -> IdentityGraph.DETERMINISTIC.contains(e.getKey().kind())).map(Map.Entry::getValue).findFirst().orElse(null);
        String memberId = deterministic != null ? deterministic : members.size() == 1 ? members.iterator().next() : null;
        boolean created = false;
        if (memberId == null && members.isEmpty() && createAs != null && !createAs.isBlank()) { memberId = createAs; created = true; }
        Map<Identifier, String> conflicts = new LinkedHashMap<>();
        for (var e : known.entrySet()) if (memberId != null && !e.getValue().equals(memberId)) conflicts.put(e.getKey(), e.getValue());
        if (memberId == null) { members.forEach(m -> known.forEach((k, v) -> { if (v.equals(m)) conflicts.put(k, v); })); return new IdentityGraph.Resolution(null, false, List.of(), conflicts); }
        List<Identifier> newly = new ArrayList<>();
        for (Identifier i : ids) {
            if (known.containsKey(i)) { if (known.get(i).equals(memberId)) jdbc.update("UPDATE identitymapping.identity_link SET last_seen = now() WHERE kind = ? AND value = ?", i.kind(), i.value()); continue; }
            link(memberId, i, source, IdentityGraph.defaultConfidence(i.kind()));
            newly.add(i);
        }
        for (Identifier i : newly) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("kind", i.kind()); attrs.put(EventTypes.ATTR_CHANNEL, channel == null ? "" : channel); attrs.put("source", source == null ? "" : source);
            if ("device_id".equals(i.kind())) attrs.put("deviceId", i.value());
            publishAction(memberId, new RewardingAction(EventTypes.ACTION_CUSTOMER_IDENTIFIED, "identity:" + memberId + ":" + i.kind() + ":" + Integer.toHexString(i.value().hashCode()), null, Instant.now(), null, attrs));
            metrics.identity("linked:" + i.kind());
        }
        return new IdentityGraph.Resolution(memberId, created, newly, conflicts);
    }

    @Transactional
    public Link link(String memberId, Identifier i, String source, int confidence) {
        if (!i.valid()) throw new IllegalArgumentException("identificatore non valido");
        jdbc.update("INSERT INTO identitymapping.identity_link(kind, value, member_id, source, confidence) VALUES (?,?,?,?,?) ON CONFLICT (kind, value) DO UPDATE SET last_seen = now(), confidence = GREATEST(identitymapping.identity_link.confidence, EXCLUDED.confidence)",
                i.kind(), i.value(), memberId, source == null ? "api" : source, confidence);
        return new Link(i.kind(), i.value(), memberId, source, confidence, Instant.now(), Instant.now());
    }

    @Transactional
    public boolean unlink(String memberId, Identifier i) {
        boolean removed = jdbc.update("DELETE FROM identitymapping.identity_link WHERE kind = ? AND value = ? AND member_id = ?", i.kind(), i.value(), memberId) > 0;
        if (removed) metrics.identity("unlinked:" + i.kind());
        return removed;
    }

    public List<Link> linksOf(String memberId) {
        return jdbc.query("SELECT kind, value, member_id, source, confidence, linked_at, last_seen FROM identitymapping.identity_link WHERE member_id = ? ORDER BY kind, linked_at",
                (rs, n) -> new Link(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant()), canonical(memberId));
    }

    /** Id canonico: segue gli alias dei membri assorbiti. */
    public String canonical(String memberId) {
        String m = memberId;
        for (int i = 0; i < 5; i++) {
            String next = jdbc.query("SELECT member_id FROM identitymapping.member_alias WHERE old_member_id = ?", rs -> rs.next() ? rs.getString(1) : null, m);
            if (next == null) return m;
            m = next;
        }
        return m;
    }

    // ---- merge -------------------------------------------------------------------------------------------------

    /**
     * Merge di due identità. {@code mergeId} è la chiave di idempotenza (RI-01): due chiamate con la stessa chiave
     * sono lo stesso merge, non due, e la seconda si limita a completare i passi mancanti. Senza chiave se ne deriva
     * una stabile dalla coppia di membri, perché la stessa coppia si fonde una volta sola.
     */
    public IdentityGraph.Merge merge(String mergeId, String fromMemberId, String intoMemberId, String reason, String actor) {
        // Riprovo con la stessa chiave: riparte dal punto in cui si era fermato, non ricomincia.
        if (mergeId != null && !mergeId.isBlank()) {
            Map<String, Object> existing = row(mergeId);
            if (existing != null) return resume(existing, fromMemberId, intoMemberId);
        }
        String from = canonical(fromMemberId), into = canonical(intoMemberId);
        // Senza chiave: se questa coppia è già stata fusa, la richiesta è un riprovo, non una seconda fusione.
        if (mergeId == null || mergeId.isBlank()) {
            Map<String, Object> existing = pair(fromMemberId, into);
            if (existing != null) return resume(existing, fromMemberId, intoMemberId);
        }
        if (from.equals(into)) throw new IllegalArgumentException("stesso membro");

        String id = mergeId == null || mergeId.isBlank() ? derivedMergeId(from, into) : mergeId;
        List<Link> moved = linksOf(from);
        step.executeWithoutResult(s -> {
            try {
                jdbc.update("""
                        INSERT INTO identitymapping.merge_history(merge_id, from_member_id, into_member_id, reason, actor, moved_links, units_moved, status)
                        VALUES (?,?,?,?,?,?::jsonb,'{}'::jsonb,?) ON CONFLICT (merge_id) DO NOTHING""",
                        id, from, into, reason, actor, MAPPER.writeValueAsString(moved), PENDING);
            } catch (Exception e) { throw new IllegalStateException("snapshot del merge non scrivibile", e); }
        });
        return advance(id);
    }

    /**
     * Riprende un merge già registrato. Dopo un merge completo il membro assorbito è un alias, quindi la coppia va
     * riconosciuta anche attraverso gli alias: altrimenti la richiesta identica sembrerebbe «stesso membro» o, peggio,
     * una chiave riusata per un'altra coppia.
     */
    private IdentityGraph.Merge resume(Map<String, Object> row, String fromRaw, String intoRaw) {
        String rowFrom = String.valueOf(row.get("from_member_id")), rowInto = String.valueOf(row.get("into_member_id"));
        if (UNMERGED.equals(String.valueOf(row.get("status"))) || row.get("unmerged_at") != null)
            throw new IllegalStateException("merge già annullato");
        boolean stessaCoppia = (rowFrom.equals(fromRaw) || rowFrom.equals(canonical(fromRaw)) || canonical(rowFrom).equals(canonical(fromRaw)))
                && (rowInto.equals(intoRaw) || rowInto.equals(canonical(intoRaw)));
        if (!stessaCoppia) throw new IllegalArgumentException("chiave di merge già usata per un'altra coppia di membri");
        return advance(String.valueOf(row.get("merge_id")));
    }

    /** Merge già registrato per questa coppia (il membro assorbito è cercato com'è arrivato, prima degli alias). */
    private Map<String, Object> pair(String fromMemberId, String into) {
        var rows = jdbc.queryForList("SELECT merge_id FROM identitymapping.merge_history WHERE from_member_id = ? AND into_member_id = ? AND status <> ? ORDER BY merged_at DESC LIMIT 1",
                fromMemberId, into, UNMERGED);
        return rows.isEmpty() ? null : row(String.valueOf(rows.get(0).get("merge_id")));
    }

    /** Compatibilità: merge senza chiave esplicita (la chiave viene derivata dalla coppia). */
    public IdentityGraph.Merge merge(String fromMemberId, String intoMemberId, String reason, String actor) {
        return merge(null, fromMemberId, intoMemberId, reason, actor);
    }

    /**
     * Porta il merge al passo successivo finché non è completo. Ogni passo è idempotente, quindi rieseguirlo dopo un
     * errore non duplica nulla: gli identificatori sono già del membro sopravvissuto, il trasferimento ha la stessa
     * {@code transferKey}, l'alias è un upsert e la chiusura è uno stato.
     */
    public IdentityGraph.Merge advance(String mergeId) {
        Map<String, Object> row = Objects.requireNonNull(row(mergeId), "merge sconosciuto: " + mergeId);
        String from = String.valueOf(row.get("from_member_id")), into = String.valueOf(row.get("into_member_id"));
        String status = String.valueOf(row.get("status"));
        if (UNMERGING.equals(status)) return advanceUnmerge(mergeId);

        if (PENDING.equals(status)) {
            step.executeWithoutResult(s -> {
                jdbc.update("UPDATE identitymapping.identity_link SET member_id = ?, last_seen = now() WHERE member_id = ?", into, from);
                mark(mergeId, LINKS_MOVED, null);
            });
            status = LINKS_MOVED;
        }
        if (LINKS_MOVED.equals(status)) {
            Map<String, Long> units = remote(mergeId, LINKS_MOVED, () -> effects.transferUnits(from, into, mergeId));
            Map<String, Long> moved = units == null ? Map.of() : units;
            step.executeWithoutResult(s -> {
                try {
                    jdbc.update("UPDATE identitymapping.merge_history SET units_moved = ?::jsonb WHERE merge_id = ?", MAPPER.writeValueAsString(moved), mergeId);
                } catch (Exception e) { throw new IllegalStateException(e); }
                mark(mergeId, UNITS_MOVED, null);
            });
            status = UNITS_MOVED;
        }
        if (UNITS_MOVED.equals(status)) {
            step.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO identitymapping.member_alias(old_member_id, member_id, merge_id) VALUES (?,?,?) ON CONFLICT (old_member_id) DO UPDATE SET member_id = EXCLUDED.member_id, merge_id = EXCLUDED.merge_id", from, into, mergeId);
                mark(mergeId, ALIASED, null);
            });
            status = ALIASED;
        }
        if (ALIASED.equals(status)) {
            remote(mergeId, ALIASED, () -> { effects.closeMember(from, "MERGED_INTO:" + into); return null; });
            step.executeWithoutResult(s -> mark(mergeId, COMPLETED, null));
            var m = merge(row(mergeId));
            publishIdentity("MERGED", m);
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("mergedMemberId", from); attrs.put("mergeId", mergeId); attrs.put("linksMoved", m.movedLinks().size());
            publishAction(into, new RewardingAction(EventTypes.ACTION_IDENTITY_MERGED, "merge:" + mergeId, null, Instant.now(), null, attrs));
            metrics.identity("merged");
            return m;
        }
        return merge(row(mergeId));
    }

    // ---- unmerge -----------------------------------------------------------------------------------------------

    /**
     * Annulla un merge: rimette gli identificatori sul membro assorbito, gli ritrasferisce le unità che gli erano
     * state tolte (quelle che ci sono ancora) e lo riattiva. Anche questo è ripartibile: lo stato passa da
     * {@code UNMERGING} a {@code UNMERGED} solo a lavoro finito.
     */
    public IdentityGraph.Merge unmerge(String mergeId, String reason) {
        Map<String, Object> row = Objects.requireNonNull(row(mergeId), "merge sconosciuto: " + mergeId);
        String status = String.valueOf(row.get("status"));
        if (UNMERGED.equals(status) || row.get("unmerged_at") != null) throw new IllegalStateException("merge già annullato");
        if (!COMPLETED.equals(status) && !UNMERGING.equals(status))
            throw new IllegalStateException("merge non completo (" + status + "): prima va portato a termine o abbandonato");
        if (COMPLETED.equals(status)) step.executeWithoutResult(s -> {
            jdbc.update("UPDATE identitymapping.merge_history SET unmerge_reason = ? WHERE merge_id = ?", reason, mergeId);
            mark(mergeId, UNMERGING, null);
        });
        return advanceUnmerge(mergeId);
    }

    private IdentityGraph.Merge advanceUnmerge(String mergeId) {
        var m0 = merge(Objects.requireNonNull(row(mergeId)));
        String from = m0.fromMemberId(), into = m0.intoMemberId();

        Map<String, Long> restored = m0.unitsMoved().isEmpty() ? Map.of()
                : remote(mergeId, UNMERGING, () -> effects.restoreUnits(into, from, m0.unitsMoved(), mergeId));
        Map<String, Long> back = restored == null ? Map.of() : restored;

        step.executeWithoutResult(s -> {
            for (Link l : m0.movedLinks())
                jdbc.update("UPDATE identitymapping.identity_link SET member_id = ? WHERE kind = ? AND value = ? AND member_id = ?", from, l.kind(), l.value(), into);
            jdbc.update("DELETE FROM identitymapping.member_alias WHERE old_member_id = ? AND merge_id = ?", from, mergeId);
            try {
                jdbc.update("UPDATE identitymapping.merge_history SET units_restored = ?::jsonb WHERE merge_id = ?", MAPPER.writeValueAsString(back), mergeId);
            } catch (Exception e) { throw new IllegalStateException(e); }
        });

        remote(mergeId, UNMERGING, () -> { effects.reopenMember(from, "UNMERGED_FROM:" + into); return null; });

        step.executeWithoutResult(s -> {
            jdbc.update("UPDATE identitymapping.merge_history SET unmerged_at = now() WHERE merge_id = ?", mergeId);
            mark(mergeId, UNMERGED, null);
        });
        var m = merge(row(mergeId));
        publishIdentity("UNMERGED", m);
        metrics.identity("unmerged");
        if (!m.shortfall().isEmpty()) {
            metrics.identity("unmerge-shortfall");
            log.warn("unmerge {}: unità non restituite perché già spese {}", mergeId, m.shortfall());
        }
        return m;
    }

    /**
     * Riprende i merge rimasti a metà: un errore di rete o un riavvio fra un passo e l'altro non li lascia lì.
     * Gira ogni minuto e prende solo quelli fermi da almeno un minuto, per non accavallarsi alla chiamata in corso.
     */
    public int reconcile() {
        List<String> stuck = jdbc.queryForList("""
                SELECT merge_id FROM identitymapping.merge_history
                WHERE status NOT IN (?, ?) AND updated_at < now() - interval '1 minute'
                ORDER BY updated_at LIMIT 50""", String.class, COMPLETED, UNMERGED);
        int done = 0;
        for (String id : stuck) {
            try { advance(id); done++; }
            catch (RuntimeException e) { log.warn("merge {} ancora incompleto: {}", id, e.getMessage()); }
        }
        if (done > 0) metrics.identity("merge-reconciled");
        return done;
    }

    public List<Map<String, Object>> mergeHistory(String memberId) {
        String m = canonical(memberId);
        return jdbc.queryForList("""
                SELECT merge_id, from_member_id, into_member_id, reason, actor, status, units_moved::text AS units_moved,
                       units_restored::text AS units_restored, failure, merged_at, unmerged_at
                FROM identitymapping.merge_history WHERE from_member_id = ? OR into_member_id = ? ORDER BY merged_at DESC""", m, m);
    }

    // ---- supporto ----------------------------------------------------------------------------------------------

    /** Esegue il passo remoto registrando l'errore sullo stato: chi riprova riparte da qui. */
    private <T> T remote(String mergeId, String status, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            step.executeWithoutResult(s -> mark(mergeId, status, message));
            throw new MergeIncomplete(mergeId, status, message, e);
        }
    }

    private void mark(String mergeId, String status, String failure) {
        jdbc.update("UPDATE identitymapping.merge_history SET status = ?, failure = ?, attempts = attempts + 1, updated_at = now() WHERE merge_id = ?",
                status, failure == null ? null : failure.substring(0, Math.min(512, failure.length())), mergeId);
    }

    private Map<String, Object> row(String mergeId) {
        var rows = jdbc.queryForList("""
                SELECT merge_id, from_member_id, into_member_id, reason, actor, status, moved_links::text AS moved_links,
                       units_moved::text AS units_moved, units_restored::text AS units_restored, merged_at, unmerged_at
                FROM identitymapping.merge_history WHERE merge_id = ?""", mergeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private IdentityGraph.Merge merge(Map<String, Object> row) {
        List<Link> moved = readJson(row.get("moved_links"), MAPPER.getTypeFactory().constructCollectionType(List.class, Link.class), List.of());
        Map<String, Long> units = readJson(row.get("units_moved"), MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Long.class), Map.of());
        Map<String, Long> restored = readJson(row.get("units_restored"), MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Long.class), Map.of());
        return new IdentityGraph.Merge(String.valueOf(row.get("merge_id")), String.valueOf(row.get("from_member_id")),
                String.valueOf(row.get("into_member_id")), (String) row.get("reason"), (String) row.get("actor"),
                moved, units, restored, String.valueOf(row.get("status")),
                instant(row.get("merged_at")), instant(row.get("unmerged_at")));
    }

    private <T> T readJson(Object raw, com.fasterxml.jackson.databind.JavaType type, T fallback) {
        if (raw == null) return fallback;
        try { return MAPPER.readValue(String.valueOf(raw), type); } catch (Exception e) { throw new IllegalStateException("snapshot del merge illeggibile", e); }
    }

    private static Instant instant(Object ts) {
        return ts instanceof java.sql.Timestamp t ? t.toInstant() : null;
    }

    /** Chiave stabile per la coppia di membri: la stessa fusione richiesta due volte non è due fusioni. */
    static String derivedMergeId(String from, String into) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((from + ">" + into).getBytes(StandardCharsets.UTF_8));
            return "auto-" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void publishIdentity(String type, IdentityGraph.Merge m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type); p.put("mergeId", m.mergeId()); p.put("fromMemberId", m.fromMemberId()); p.put("intoMemberId", m.intoMemberId()); p.put("reason", m.reason());
        p.put("movedLinks", m.movedLinks().stream().map(l -> Map.of("kind", l.kind(), "value", l.value())).toList());
        p.put("unitsMoved", m.unitsMoved()); p.put("unitsRestored", m.unitsRestored()); p.put("shortfall", m.shortfall());
        p.put("at", Instant.now().toString());
        kafka.send(EventTypes.TOPIC_IDENTITIES, m.intoMemberId(), CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.IDENTITY_V1, SOURCE, "member:" + m.intoMemberId(), p)));
    }

    private void publishAction(String memberId, RewardingAction a) {
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.action(SOURCE, memberId, a)));
    }
}
