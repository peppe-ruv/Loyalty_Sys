package it.iren.loyalty.catalogredemption.domain;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Riscatto atomico (RF-15): controlli di ammissibilità ({@link RedemptionPolicy}), riserva stock con lock, addebito
 * sul ledger, eventuale prelievo del codice dal lotto, registrazione. Se il ledger rifiuta (saldo insufficiente) la
 * transazione locale fa rollback e stock e codice tornano disponibili. La consegna (buono dal fornitore, cashback in
 * bolletta via SAP, spedizione) è una saga separata avviata dall'evento di riscatto (D10, RewardFulfiller).
 * {@link #grant} assegna un premio senza costo in punti: "instant reward" di una regola o benefit di tier (RF-76).
 */
@Service
public class RedemptionService {
    public record Result(UUID redemptionId, String status, String code, Instant codeExpiresAt) {}
    public static class RedemptionRejected extends RuntimeException { public RedemptionRejected(String m) { super(m); } }

    private final JdbcTemplate jdbc;
    private final RestClient ledger;
    private final CouponPool coupons;
    private final it.iren.loyalty.common.metrics.LoyaltyMetrics metrics;
    private final org.springframework.kafka.core.KafkaTemplate<String, byte[]> kafka;

    public RedemptionService(JdbcTemplate jdbc, RestClient.Builder builder, CouponPool coupons, it.iren.loyalty.common.metrics.LoyaltyMetrics metrics, org.springframework.kafka.core.KafkaTemplate<String, byte[]> kafka) {
        this.jdbc = jdbc;
        this.coupons = coupons;
        this.metrics = metrics;
        this.kafka = kafka;
        this.ledger = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
    }

    @Transactional
    @CircuitBreaker(name = "ledger")
    public Result redeem(String memberId, int memberTierOrder, Set<String> memberSegments, long premioAvailable, UUID rewardId) {
        RewardDefinition r = lockReward(rewardId);
        Instant now = Instant.now();
        long mine = jdbc.queryForObject("SELECT count(*) FROM catalogredemption.redemption WHERE member_id = ? AND reward_id = ? AND status <> 'CANCELLED'", Long.class, memberId, rewardId);
        long today = jdbc.queryForObject("SELECT count(*) FROM catalogredemption.redemption WHERE member_id = ? AND reward_id = ? AND status <> 'CANCELLED' AND requested_at >= ?", Long.class, memberId, rewardId,
                Timestamp.from(now.atZone(ZoneId.of("Europe/Rome")).toLocalDate().atStartOfDay(ZoneId.of("Europe/Rome")).toInstant()));
        var verdict = RedemptionPolicy.check(r, new RedemptionPolicy.MemberState(memberTierOrder, memberSegments, premioAvailable, mine, today), now);
        if (verdict != RedemptionPolicy.Reason.OK) { metrics.redemption(verdict.name(), r.type().name()); throw new RedemptionRejected(verdict.name()); }

        UUID id = UUID.randomUUID();
        if (r.pointsCost() > 0) {
            try {
                ledger.post().uri("/v1/ledger/debits").body(Map.of("memberId", memberId, "actionKey", "redemption:" + id + ":DEBIT", "points", r.pointsCost(), "reason", "REDEMPTION")).retrieve().toBodilessEntity();
            } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
                throw new RedemptionRejected("INSUFFICIENT_BALANCE");
            }
        }
        return record(id, memberId, r, now);
    }

    /** Assegnazione senza punti (RF-76): regola "instant reward", benefit di tier, gesto del customer care. Idempotente per chiave. */
    @Transactional
    public Result grant(String memberId, UUID rewardId, String grantKey) {
        var existing = jdbc.queryForList("SELECT id, status, code FROM catalogredemption.redemption WHERE grant_key = ?", grantKey);
        if (!existing.isEmpty()) {
            var e = existing.get(0);
            return new Result((UUID) e.get("id"), (String) e.get("status"), (String) e.get("code"), null);
        }
        RewardDefinition r = lockReward(rewardId);
        if (!r.unlimitedStock() && r.stock() <= 0) throw new RedemptionRejected("OUT_OF_STOCK");
        UUID id = UUID.randomUUID();
        Result out = record(id, memberId, r, Instant.now());
        jdbc.update("UPDATE catalogredemption.redemption SET grant_key = ? WHERE id = ?", grantKey, id);
        return out;
    }

    private Result record(UUID id, String memberId, RewardDefinition r, Instant now) {
        if (!r.unlimitedStock()) jdbc.update("UPDATE catalogredemption.reward SET stock = stock - 1 WHERE id = ?", UUID.fromString(r.id()));
        String code = null;
        if (r.deliversCode() && r.couponPoolId() != null) {
            code = coupons.take(r.couponPoolId(), id).orElseThrow(() -> new RedemptionRejected("COUPON_POOL_EMPTY"));
        }
        Instant codeExpiry = r.codeValidityDays() > 0 ? now.plus(Duration.ofDays(r.codeValidityDays())) : null;
        String status = r.type() == RewardDefinition.Type.PHYSICAL ? RedemptionState.CONFIRMED.name()
                : code != null ? RedemptionState.DELIVERED.name() : RedemptionState.CONFIRMED.name();
        jdbc.update("INSERT INTO catalogredemption.redemption(id, member_id, reward_id, points, status, code, code_expires_at, requested_at, delivered_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, memberId, UUID.fromString(r.id()), r.pointsCost(), status, code, codeExpiry == null ? null : Timestamp.from(codeExpiry), Timestamp.from(now),
                code != null ? Timestamp.from(now) : null);
        metrics.redemption(status, r.type().name());
        publish(memberId, id, r.id(), r.type().name(), r.name(), status, r.pointsCost(), code);
        return new Result(id, status, code, codeExpiry);
    }

    /** Cambio di stato massivo (RF-103): stessi controlli, un esito per riga. */
    @Transactional
    public Map<UUID, String> transitionAll(List<UUID> ids, RedemptionState to, String actor) {
        Map<UUID, String> out = new java.util.LinkedHashMap<>();
        for (UUID id : ids) { try { out.put(id, transition(id, to, actor, false)); } catch (RuntimeException e) { out.put(id, "ERROR:" + e.getMessage()); } }
        return out;
    }

    /** Transizioni di stato (RF-16, RF-17, RF-76, RF-103): annullo/rifiuto/reso con storno punti; lavorazione, consegna, "usato". */
    @Transactional
    public String transition(UUID redemptionId, RedemptionState to, String actor, boolean byMember) {
        var row = jdbc.queryForMap("SELECT member_id, status, points, reward_id, requested_at FROM catalogredemption.redemption WHERE id = ? FOR UPDATE", redemptionId);
        RedemptionState from = RedemptionState.valueOf((String) row.get("status"));
        if (byMember && to == RedemptionState.CANCELLED) {
            if (!from.cancellableByMember()) throw new RedemptionRejected("NOT_CANCELLABLE");
            int graceHours = Integer.parseInt(System.getenv().getOrDefault("REDEMPTION_CANCEL_HOURS", "48"));
            if (((Timestamp) row.get("requested_at")).toInstant().plus(Duration.ofHours(graceHours)).isBefore(Instant.now())) throw new RedemptionRejected("CANCEL_WINDOW_ELAPSED");
        }
        if (!from.canGo(to)) throw new RedemptionRejected("ILLEGAL_TRANSITION_" + from + "_" + to);
        if (to.refunds()) {
            long points = ((Number) row.get("points")).longValue();
            if (points > 0) ledger.post().uri("/v1/ledger/reversals/{k}", "redemption:" + redemptionId + ":DEBIT").retrieve().toBodilessEntity();
            jdbc.update("UPDATE catalogredemption.reward SET stock = stock + 1 WHERE id = ? AND stock >= 0", row.get("reward_id"));
            jdbc.update("UPDATE catalogredemption.coupon SET redemption_id = NULL, assigned_at = NULL WHERE redemption_id = ?", redemptionId);
        }
        jdbc.update("UPDATE catalogredemption.redemption SET status = ?, delivered_at = CASE WHEN ? = 'DELIVERED' THEN now() ELSE delivered_at END, used_at = CASE WHEN ? = 'USED' THEN now() ELSE used_at END, last_actor = ? WHERE id = ?",
                to.name(), to.name(), to.name(), actor, redemptionId);
        jdbc.update("INSERT INTO catalogredemption.redemption_status_history(redemption_id, from_status, to_status, actor) VALUES (?,?,?,?)", redemptionId, from.name(), to.name(), actor);
        var rw = jdbc.queryForMap("SELECT type, name FROM catalogredemption.reward WHERE id = ?", row.get("reward_id"));
        publish((String) row.get("member_id"), redemptionId, row.get("reward_id").toString(), (String) rw.get("type"), (String) rw.get("name"), to.name(), ((Number) row.get("points")).longValue(), null);
        metrics.redemption(to.name(), (String) rw.get("type"));
        return to.name();
    }

    /** Evento REDEMPTION (RI-06): stato di riscatti e vincite per CRM, BI e notifiche (RF-77); dopo il commit, in produzione via outbox. */
    private void publish(String memberId, UUID redemptionId, String rewardId, String rewardType, String rewardName, String status, long points, String code) {
        Map<String, Object> data = new java.util.HashMap<>(Map.of("redemptionId", redemptionId.toString(), "rewardId", rewardId, "rewardType", rewardType, "rewardName", rewardName == null ? "" : rewardName, "status", status, "points", points));
        if (code != null) data.put("code", code);
        kafka.send(it.iren.loyalty.common.event.EventTypes.TOPIC_REDEMPTIONS, memberId, it.iren.loyalty.common.event.CanonicalEvents.serialize(
                it.iren.loyalty.common.event.CanonicalEvents.of(it.iren.loyalty.common.event.EventTypes.REDEMPTION_V1, "urn:iren:loyalty:catalog", "member:" + memberId, data)));
    }

    private RewardDefinition lockReward(UUID rewardId) {
        var m = jdbc.queryForMap("SELECT id, name, type, value_eur, points_cost, min_tier_order, stock, limit_per_member, limit_per_member_per_day, visible_from, visible_to, active_from, active_to, target_segments, category, coupon_pool_id, code_validity_days, status FROM catalogredemption.reward WHERE id = ? FOR UPDATE", rewardId);
        String segs = (String) m.get("target_segments");
        return new RewardDefinition(m.get("id").toString(), (String) m.get("name"), RewardDefinition.Type.valueOf((String) m.get("type")), (BigDecimal) m.get("value_eur"),
                ((Number) m.get("points_cost")).longValue(), ((Number) m.get("min_tier_order")).intValue(), ((Number) m.get("stock")).longValue(),
                ((Number) m.get("limit_per_member")).intValue(), ((Number) m.get("limit_per_member_per_day")).intValue(),
                ts(m.get("visible_from")), ts(m.get("visible_to")), ts(m.get("active_from")), ts(m.get("active_to")),
                segs == null || segs.isBlank() ? Set.of() : Set.of(segs.split(",")), (String) m.get("category"), (String) m.get("coupon_pool_id"),
                ((Number) m.get("code_validity_days")).intValue(), "ACTIVE".equals(m.get("status")));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
