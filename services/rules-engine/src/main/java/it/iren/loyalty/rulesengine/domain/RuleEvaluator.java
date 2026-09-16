package it.iren.loyalty.rulesengine.domain;

import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.common.event.RewardingAction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Valuta le regole su un'azione e produce le poste da accreditare. Pura e senza stato: la stessa classe
 * alimenta il simulatore del backoffice (RF-08) e il consumer Kafka.
 */
public class RuleEvaluator {
    public record Posting(String ruleId, String ruleVersion, Currency currency, long amount) {}
    public record Context(String memberTier, long rewardPointsAlreadyEarnedInPeriod) {}

    public List<Posting> evaluate(RewardingAction action, List<Rule> candidates, Context ctx) {
        Instant at = action.occurredAt() == null ? Instant.now() : action.occurredAt();
        List<Posting> out = new ArrayList<>();
        boolean nonStackableApplied = false;
        for (Rule r : candidates) {
            if (!r.actionType().equals(action.actionType()) || !r.isActiveAt(at)) continue;
            if (!matches(r, action.attributes())) continue;
            if (nonStackableApplied && !r.stackable()) continue;

            BigDecimal mult = r.multiplierFor(ctx.memberTier());
            long reward = BigDecimal.valueOf(r.rewardPoints()).multiply(mult).setScale(0, RoundingMode.HALF_UP).longValue();
            if (r.capPerMemberPerPeriod() > 0) {
                long room = Math.max(0, r.capPerMemberPerPeriod() - ctx.rewardPointsAlreadyEarnedInPeriod());
                reward = Math.min(reward, room);
            }
            if (reward != 0) out.add(new Posting(r.id(), r.version(), Currency.PREMIO, reward));
            if (r.statusPoints() != 0) out.add(new Posting(r.id(), r.version(), Currency.STATUS, r.statusPoints()));
            if (!r.stackable()) nonStackableApplied = true;
        }
        return out;
    }

    static boolean matches(Rule r, Map<String, Object> attrs) {
        if (r.conditions() == null || r.conditions().isEmpty()) return true;
        for (Rule.Condition c : r.conditions()) {
            Object v = attrs == null ? null : attrs.get(c.attribute());
            if (!test(c, v)) return false;
        }
        return true;
    }

    private static boolean test(Rule.Condition c, Object v) {
        String s = v == null ? null : v.toString();
        switch (c.op()) {
            case EQ: return Objects.equals(s, c.value());
            case NE: return !Objects.equals(s, c.value());
            case IN: return s != null && Arrays.asList(c.value().split(",")).contains(s);
            default:
                if (s == null) return false;
                int cmp;
                try { cmp = new BigDecimal(s).compareTo(new BigDecimal(c.value())); }
                catch (NumberFormatException e) { return false; }
                return switch (c.op()) {
                    case GT -> cmp > 0; case GTE -> cmp >= 0; case LT -> cmp < 0; case LTE -> cmp <= 0;
                    default -> false;
                };
        }
    }
}
