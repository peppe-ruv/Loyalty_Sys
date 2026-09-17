package io.loyaltyhub.ingressadapters.codes;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.ingressadapters.publish.ActionPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Verifica e consumo di un codice (RF-69) in una transazione con lock sul codice: nessun lotto monouso usato due volte. */
@Service
public class CodeRedemptionService {
    public record Outcome(PromoCode.Verdict verdict, String campaign) {}

    private final JdbcTemplate jdbc;
    private final ActionPublisher publisher;

    public CodeRedemptionService(JdbcTemplate jdbc, ActionPublisher publisher) { this.jdbc = jdbc; this.publisher = publisher; }

    @Transactional
    public Outcome redeem(String memberId, String rawCode, String channel) {
        String code = PromoCode.normalize(rawCode);
        Optional<PromoCode> found = jdbc.query("SELECT code, campaign, kind, valid_from, valid_to, max_uses, max_uses_per_member, active FROM ingressadapters.promo_code WHERE code = ? FOR UPDATE", rs -> {
            if (!rs.next()) return Optional.<PromoCode>empty();
            return Optional.of(new PromoCode(rs.getString(1), rs.getString(2), PromoCode.Kind.valueOf(rs.getString(3)),
                    rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                    rs.getInt(6), rs.getInt(7), rs.getBoolean(8)));
        }, code);
        if (found.isEmpty()) return new Outcome(PromoCode.Verdict.UNKNOWN, null);
        PromoCode pc = found.get();
        Instant now = Instant.now();
        long total = jdbc.queryForObject("SELECT count(*) FROM ingressadapters.code_use WHERE code = ?", Long.class, code);
        long mine = jdbc.queryForObject("SELECT count(*) FROM ingressadapters.code_use WHERE code = ? AND member_id = ?", Long.class, code, memberId);
        PromoCode.Verdict v = pc.verdict(now, total, mine);
        if (v != PromoCode.Verdict.OK) return new Outcome(v, pc.campaign());
        jdbc.update("INSERT INTO ingressadapters.code_use(code, member_id, used_at) VALUES (?,?,?)", code, memberId, Timestamp.from(now));
        String key = "code:" + code + ":" + memberId + ":" + (mine + 1);
        var action = new RewardingAction(EventTypes.ACTION_CODE_REDEEMED, key, code, now, null,
                Map.of(EventTypes.ATTR_CODE, code, "campaign", pc.campaign(), "kind", pc.kind().name(), EventTypes.ATTR_CHANNEL, channel == null ? "web" : channel));
        publisher.publish(CanonicalEvents.action("urn:loyaltyhub:source:codes", memberId, action));
        return new Outcome(PromoCode.Verdict.OK, pc.campaign());
    }
}
