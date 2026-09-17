package it.iren.loyalty.identitymapping.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.common.metrics.LoyaltyMetrics;
import it.iren.loyalty.identitymapping.domain.IdentityGraph;
import it.iren.loyalty.identitymapping.domain.IdentityGraph.Identifier;
import it.iren.loyalty.identitymapping.domain.IdentityGraph.Link;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Risoluzione, collegamento, merge e unmerge delle identità (RF-136). Ogni nuovo collegamento produce l'azione
 * CUSTOMER_IDENTIFIED (le campagne possono premiare il primo riconoscimento su un canale); ogni merge produce
 * IDENTITY_V1 (MERGED/UNMERGED) per gli altri servizi e l'azione IDENTITY_MERGED; le unità del membro assorbito sono
 * trasferite sul ledger (porta {@link MergeEffects}), mai duplicate né perse.
 */
@Service
public class IdentityService {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private static final String SOURCE = "urn:iren:loyalty:identity-mapping";

    /** Effetti del merge sugli altri domini: trasferimento unità (ledger), chiusura del membro assorbito (member-service). */
    public interface MergeEffects {
        Map<String, Long> transferUnits(String fromMemberId, String intoMemberId, String mergeId);
        void closeMember(String memberId, String reason);
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final MergeEffects effects;
    private final LoyaltyMetrics metrics;

    public IdentityService(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, MergeEffects effects, LoyaltyMetrics metrics) {
        this.jdbc = jdbc; this.kafka = kafka; this.effects = effects; this.metrics = metrics;
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

    /** Merge: sposta gli identificatori, trasferisce le unità, chiude il membro assorbito, registra lo snapshot e pubblica. */
    @Transactional
    public IdentityGraph.Merge merge(String fromMemberId, String intoMemberId, String reason, String actor) {
        String from = canonical(fromMemberId), into = canonical(intoMemberId);
        if (from.equals(into)) throw new IllegalArgumentException("stesso membro");
        List<Link> moved = linksOf(from);
        String mergeId = UUID.randomUUID().toString();
        jdbc.update("UPDATE identitymapping.identity_link SET member_id = ?, last_seen = now() WHERE member_id = ?", into, from);
        Map<String, Long> units;
        try { units = effects.transferUnits(from, into, mergeId); } catch (Exception e) { throw new IllegalStateException("trasferimento unità fallito: " + e.getMessage(), e); }
        try {
            jdbc.update("INSERT INTO identitymapping.merge_history(merge_id, from_member_id, into_member_id, reason, actor, moved_links, units_moved) VALUES (?,?,?,?,?,?::jsonb,?::jsonb)",
                    mergeId, from, into, reason, actor, MAPPER.writeValueAsString(moved), MAPPER.writeValueAsString(units == null ? Map.of() : units));
        } catch (Exception e) { throw new IllegalStateException(e); }
        jdbc.update("INSERT INTO identitymapping.member_alias(old_member_id, member_id, merge_id) VALUES (?,?,?) ON CONFLICT (old_member_id) DO UPDATE SET member_id = EXCLUDED.member_id, merge_id = EXCLUDED.merge_id", from, into, mergeId);
        try { effects.closeMember(from, "MERGED_INTO:" + into); } catch (Exception ignored) { /* best effort: il membro assorbito resta alias */ }
        var m = new IdentityGraph.Merge(mergeId, from, into, reason, actor, moved, units == null ? Map.of() : units, Instant.now(), null);
        publishIdentity("MERGED", m);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("mergedMemberId", from); attrs.put("mergeId", mergeId); attrs.put("linksMoved", moved.size());
        publishAction(into, new RewardingAction(EventTypes.ACTION_IDENTITY_MERGED, "merge:" + mergeId, null, Instant.now(), null, attrs));
        metrics.identity("merged");
        return m;
    }

    /** Unmerge: ripristina gli identificatori del membro assorbito dallo snapshot e riattiva l'id; le unità già trasferite restano dove sono (audit nel ledger). */
    @Transactional
    public IdentityGraph.Merge unmerge(String mergeId, String reason) {
        var row = jdbc.queryForMap("SELECT from_member_id, into_member_id, moved_links::text AS links, unmerged_at FROM identitymapping.merge_history WHERE merge_id = ? FOR UPDATE", mergeId);
        if (row.get("unmerged_at") != null) throw new IllegalStateException("merge già annullato");
        String from = String.valueOf(row.get("from_member_id")), into = String.valueOf(row.get("into_member_id"));
        List<Link> moved;
        try { moved = MAPPER.readValue(String.valueOf(row.get("links")), MAPPER.getTypeFactory().constructCollectionType(List.class, Link.class)); } catch (Exception e) { throw new IllegalStateException(e); }
        for (Link l : moved) jdbc.update("UPDATE identitymapping.identity_link SET member_id = ? WHERE kind = ? AND value = ? AND member_id = ?", from, l.kind(), l.value(), into);
        jdbc.update("DELETE FROM identitymapping.member_alias WHERE old_member_id = ? AND merge_id = ?", from, mergeId);
        jdbc.update("UPDATE identitymapping.merge_history SET unmerged_at = now(), unmerge_reason = ? WHERE merge_id = ?", reason, mergeId);
        var m = new IdentityGraph.Merge(mergeId, from, into, reason, null, moved, Map.of(), Instant.now(), Instant.now());
        publishIdentity("UNMERGED", m);
        metrics.identity("unmerged");
        return m;
    }

    public List<Map<String, Object>> mergeHistory(String memberId) {
        String m = canonical(memberId);
        return jdbc.queryForList("SELECT merge_id, from_member_id, into_member_id, reason, actor, units_moved::text AS units_moved, merged_at, unmerged_at FROM identitymapping.merge_history WHERE from_member_id = ? OR into_member_id = ? ORDER BY merged_at DESC", m, m);
    }

    private void publishIdentity(String type, IdentityGraph.Merge m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type); p.put("mergeId", m.mergeId()); p.put("fromMemberId", m.fromMemberId()); p.put("intoMemberId", m.intoMemberId()); p.put("reason", m.reason());
        p.put("movedLinks", m.movedLinks().stream().map(l -> Map.of("kind", l.kind(), "value", l.value())).toList()); p.put("unitsMoved", m.unitsMoved()); p.put("at", Instant.now().toString());
        kafka.send(EventTypes.TOPIC_IDENTITIES, m.intoMemberId(), CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.IDENTITY_V1, SOURCE, "member:" + m.intoMemberId(), p)));
    }

    private void publishAction(String memberId, RewardingAction a) {
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.action(SOURCE, memberId, a)));
    }
}
