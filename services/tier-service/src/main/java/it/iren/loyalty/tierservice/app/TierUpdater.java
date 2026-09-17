package it.iren.loyalty.tierservice.app;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.tierservice.domain.TierPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Aggiorna i punti STATUS dell'anno e il tier del membro dai movimenti del ledger (RF-10): upgrade immediato; ogni
 * cambio produce l'evento TIER_CHANGED (per CRM, BI, notifiche) e l'azione interna TIER_CHANGED (RF-13).
 * A inizio anno programma esegue la verifica annuale con discesa morbida (RF-11, RF-22) e azzera i punti status.
 */
@Component
public class TierUpdater {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final TierPolicy policy = TierPolicy.example();
    private final it.iren.loyalty.common.metrics.LoyaltyMetrics metrics;

    public TierUpdater(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, it.iren.loyalty.common.metrics.LoyaltyMetrics metrics) { this.jdbc = jdbc; this.kafka = kafka; this.metrics = metrics; }

    @KafkaListener(topics = EventTypes.TOPIC_MOVEMENTS, groupId = "tier-service")
    @Transactional
    public void onMovement(byte[] payload) {
        var event = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(event, Map.class);
        if (!"STATUS".equals(m.get("currency"))) return;
        long amount = ((Number) m.get("amount")).longValue();
        String memberId = CanonicalEvents.memberId(event);
        // L'outbox del ledger è at-least-once: senza memoria dei movimenti già applicati un replay
        // gonfierebbe i punti status dell'anno, e con essi il tier (RI-08).
        if (!firstTime(String.valueOf(m.get("movementId")), memberId)) return;
        int year = LocalDate.now(ZoneId.of("Europe/Rome")).getYear();
        jdbc.update("INSERT INTO tierservice.member_tier(member_id, tier, status_points_year, program_year) VALUES (?, 'BASE', 0, ?) ON CONFLICT (member_id) DO NOTHING", memberId, year);
        var row = jdbc.queryForMap("SELECT tier, status_points_year FROM tierservice.member_tier WHERE member_id = ? FOR UPDATE", memberId);
        long points = Math.max(0, ((Number) row.get("status_points_year")).longValue() + amount);
        TierPolicy.Tier current = policy.byCode((String) row.get("tier"));
        TierPolicy.Tier next = policy.duringYear(current, points);
        jdbc.update("UPDATE tierservice.member_tier SET status_points_year = ?, tier = ?, updated_at = now() WHERE member_id = ?", points, next.code(), memberId);
        if (!next.equals(current)) changed(memberId, current.code(), next.code(), "UPGRADE");
    }

    /** Registra il movimento come applicato; false se c'era già (replay del topic). */
    private boolean firstTime(String movementId, String memberId) {
        return jdbc.update("INSERT INTO tierservice.applied_movement(movement_id, member_id) VALUES (CAST(? AS uuid), ?) ON CONFLICT DO NOTHING",
                movementId, memberId) == 1;
    }

    @Scheduled(cron = "${tiers.year-end-cron:0 30 0 1 1 *}", zone = "Europe/Rome")
    @Transactional
    public void yearEnd() {
        int year = LocalDate.now(ZoneId.of("Europe/Rome")).getYear();
        jdbc.query("SELECT member_id, tier, status_points_year FROM tierservice.member_tier WHERE program_year < ? FOR UPDATE", rs -> {
            String memberId = rs.getString(1);
            TierPolicy.Tier current = policy.byCode(rs.getString(2));
            TierPolicy.Tier next = policy.atYearEnd(current, rs.getLong(3));
            jdbc.update("UPDATE tierservice.member_tier SET tier = ?, status_points_year = 0, program_year = ?, updated_at = now() WHERE member_id = ?", next.code(), year, memberId);
            if (!next.equals(current)) changed(memberId, current.code(), next.code(), next.order() < current.order() ? "YEAR_END_DOWNGRADE" : "YEAR_END_CONFIRMED");
        }, year);
    }

    private void changed(String memberId, String from, String to, String reason) {
        jdbc.update("INSERT INTO tierservice.tier_history(member_id, from_tier, to_tier, reason) VALUES (?,?,?,?)", memberId, from, to, reason);
        Instant now = Instant.now();
        var e = CanonicalEvents.of(EventTypes.TIER_CHANGED_V1, "urn:iren:loyalty:tiers", "member:" + memberId, Map.of("fromTier", from, "toTier", to, "reason", reason, "at", now.toString()));
        kafka.send(EventTypes.TOPIC_TIERS, memberId, CanonicalEvents.serialize(e));
        // Chiave stabile (fonte:riferimento:evento, RI-01): con l'orologio, un replay avrebbe emesso
        // un'azione nuova a ogni passaggio invece di essere riconosciuta come la stessa.
        String key = "tiers:" + memberId + "." + LocalDate.now(ZoneId.of("Europe/Rome")).getYear() + "." + to + "." + reason + ":" + EventTypes.ACTION_TIER_CHANGED;
        var a = new RewardingAction(EventTypes.ACTION_TIER_CHANGED, key, to, now, null, Map.of("fromTier", from, "toTier", to, "reason", reason));
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.ACTION_V1, "urn:iren:loyalty:tiers", "member:" + memberId, a)));
        metrics.memberEvent("TIER_" + reason);
    }
}
