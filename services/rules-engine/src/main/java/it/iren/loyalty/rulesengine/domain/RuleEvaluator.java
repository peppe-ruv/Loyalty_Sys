package it.iren.loyalty.rulesengine.domain;

import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.common.event.TransactionLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Valuta le regole su un'azione e produce le poste da accreditare. Pura e senza stato: la stessa classe
 * alimenta il simulatore del backoffice (RF-08) e il consumer Kafka.
 */
public class RuleEvaluator {
    /** Posta prodotta da una regola; {@code lockDays} > 0 = punti in sospeso fino alla scadenza della finestra (RF-66). */
    public record Posting(String ruleId, String ruleVersion, Currency currency, long amount, int lockDays, String autoRewardId) {
        public Posting(String ruleId, String ruleVersion, Currency currency, long amount) { this(ruleId, ruleVersion, currency, amount, 0, null); }
        @Override public String toString() {
            return "Posting[ruleId=" + ruleId + ", ruleVersion=" + ruleVersion + ", currency=" + currency + ", amount=" + amount
                    + (lockDays > 0 ? ", lockDays=" + lockDays : "") + (autoRewardId != null ? ", autoReward=" + autoRewardId : "") + "]";
        }
    }

    /**
     * Stato del membro necessario alla valutazione: tier, punti già maturati nel periodo, segmenti di appartenenza,
     * usi della regola nel periodo (per {@link Rule.Limits#maxUsesPerMemberPerPeriod()}).
     */
    public record Context(String memberTier, long rewardPointsAlreadyEarnedInPeriod, Set<String> memberSegments, Map<String, Integer> usesInPeriodByRule) {
        public Context(String memberTier, long rewardPointsAlreadyEarnedInPeriod) { this(memberTier, rewardPointsAlreadyEarnedInPeriod, Set.of(), Map.of()); }
        int usesOf(String ruleId) { return usesInPeriodByRule == null ? 0 : usesInPeriodByRule.getOrDefault(ruleId, 0); }
    }

    public List<Posting> evaluate(RewardingAction action, List<Rule> candidates, Context ctx) {
        Instant at = action.occurredAt() == null ? Instant.now() : action.occurredAt();
        Map<String, Object> attrs = action.attributes() == null ? Map.of() : action.attributes();
        String channel = attrs.get(EventTypes.ATTR_CHANNEL) == null ? null : attrs.get(EventTypes.ATTR_CHANNEL).toString();
        List<TransactionLine> lines = TransactionLine.fromAttribute(attrs.get(EventTypes.ATTR_LINES));
        List<Posting> out = new ArrayList<>();
        boolean nonStackableApplied = false;
        for (Rule r : candidates) {
            if (!r.actionType().equals(action.actionType()) || !r.isActiveAt(at)) continue;
            if (!r.target().admits(ctx.memberTier(), ctx.memberSegments(), channel)) continue;
            if (!matches(r, attrs)) continue;
            if (nonStackableApplied && !r.stackable()) continue;
            int maxUses = r.limits().maxUsesPerMemberPerPeriod();
            if (maxUses > 0 && ctx.usesOf(r.id()) >= maxUses) continue;

            BigDecimal base = BigDecimal.valueOf(r.rewardPoints()).add(proportional(r, attrs, lines));
            BigDecimal mult = r.earning().multiplierOrOne().multiply(r.multiplierFor(ctx.memberTier()));
            long reward = base.multiply(mult).setScale(0, RoundingMode.HALF_UP).longValue();
            if (r.capPerMemberPerPeriod() > 0) {
                long room = Math.max(0, r.capPerMemberPerPeriod() - ctx.rewardPointsAlreadyEarnedInPeriod());
                reward = Math.min(reward, room);
            }
            String autoReward = r.earning().autoRewardId();
            if (reward != 0 || autoReward != null) out.add(new Posting(r.id(), r.version(), Currency.PREMIO, reward, r.limits().lockDays(), autoReward));
            if (r.statusPoints() != 0) out.add(new Posting(r.id(), r.version(), Currency.STATUS, r.statusPoints(), 0, null));
            if (!r.stackable()) nonStackableApplied = true;
            if (r.limits().stopAfter()) break;
        }
        return out;
    }

    /** Componente proporzionale (RF-63): punti per euro sull'importo, o sulla somma delle righe ammesse dal filtro. */
    static BigDecimal proportional(Rule r, Map<String, Object> attrs, List<TransactionLine> lines) {
        BigDecimal perEur = r.earning().pointsPerEurOrZero();
        if (perEur.signum() == 0) return BigDecimal.ZERO;
        BigDecimal amount;
        Rule.LineFilter filter = r.earning().lineFilter();
        if (!lines.isEmpty() && filter != null) {
            amount = lines.stream().filter(filter::accepts).map(TransactionLine::amountOrZero).reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            Object v = attrs.get(r.earning().amountAttributeOrDefault());
            if (v == null) return BigDecimal.ZERO;
            try { amount = new BigDecimal(v.toString()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
        }
        return amount.multiply(perEur);
    }

    static boolean matches(Rule r, Map<String, Object> attrs) {
        if (r.conditions() == null || r.conditions().isEmpty()) return true;
        for (Rule.Condition c : r.conditions()) {
            Object v = attrs == null ? null : attrs.get(c.attribute());
            if (!test(c, v, attrs)) return false;
        }
        return true;
    }

    private static boolean test(Rule.Condition c, Object v, Map<String, Object> attrs) {
        String s = v == null ? null : v.toString();
        switch (c.op()) {
            case EQ: return Objects.equals(s, c.value());
            case NE: return !Objects.equals(s, c.value());
            case IN: return s != null && Arrays.asList(c.value().split(",")).contains(s);
            case EXISTS: return v != null;
            case CONTAINS:
                if (v instanceof List<?> list) return list.stream().map(String::valueOf).anyMatch(c.value()::equals);
                return s != null && s.contains(c.value());
            case MATCHES:
                try { return s != null && s.matches(c.value()); } catch (PatternSyntaxException e) { return false; }
            case GEO_WITHIN: return geoWithin(c.value(), attrs);
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

    /** Geolocalizzazione (RF-67): valore {@code "lat,lon,raggioMetri"}; distanza haversine dagli attributi lat/lon. */
    static boolean geoWithin(String spec, Map<String, Object> attrs) {
        try {
            String[] p = spec.split(",");
            double lat0 = Double.parseDouble(p[0].trim()), lon0 = Double.parseDouble(p[1].trim()), radius = Double.parseDouble(p[2].trim());
            Object la = attrs.get(EventTypes.ATTR_LAT), lo = attrs.get(EventTypes.ATTR_LON);
            if (la == null || lo == null) return false;
            return haversineMeters(lat0, lon0, Double.parseDouble(la.toString()), Double.parseDouble(lo.toString())) <= radius;
        } catch (RuntimeException e) { return false; }
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000d;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
