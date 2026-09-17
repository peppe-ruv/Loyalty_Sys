package io.loyaltyhub.rulesengine.campaign;

import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.event.TransactionLine;
import io.loyaltyhub.rulesengine.domain.Rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * Valuta le campagne su un'azione e produce gli effetti da applicare (RF-80..RF-86). Pura: alimenta il consumer, il
 * simulatore (RF-08) e i test. Le espressioni vedono il contesto come variabili: {@code #member}, {@code #transaction}
 * (attributi dell'azione), {@code #lines}, {@code #event}, {@code #wallet(code)} tramite {@code #wallets}, {@code #referrer},
 * {@code #executionContext.processedAt}, {@code #fn} (funzioni).
 */
public class CampaignEvaluator {
    /** Stato del membro visto dalla campagna: tier, segmenti, wallet (disponibile/maturato/speso), attributi custom, presentatore. */
    public record MemberContext(String memberId, String tier, Set<String> segments, Map<String, WalletView> wallets, Map<String, Object> attributes, Set<String> badges, String referrerId, Instant enrolledAt) {
        public static MemberContext simple(String memberId, String tier) { return new MemberContext(memberId, tier, Set.of(), Map.of(), Map.of(), Set.of(), null, null); }
    }
    public record WalletView(long active, long earned, long spent, long pending, long blocked, long expired) {}
    /** Contatori per i limiti (RF-82): esecuzioni e unità già erogate al membro nel periodo, unità già erogate in totale dalla campagna. */
    public record Usage(int memberTriggersInPeriod, long memberUnitsInPeriod, long campaignUnitsTotal) {
        public static final Usage NONE = new Usage(0, 0, 0);
    }

    /** Effetto risolto: unità (con scadenza/sospensione calcolate), premio, badge, attributo, tier, evento. */
    public record Outcome(String campaignId, String version, String ruleId, Campaign.Effect.Type type, String wallet, long units, String reference, Map<String, String> params, Instant expiresAt, Instant pendingUntil) {
        public boolean isUnits() { return type == Campaign.Effect.Type.ADD_UNITS || type == Campaign.Effect.Type.DEDUCT_UNITS; }
        public long signedUnits() { return type == Campaign.Effect.Type.DEDUCT_UNITS ? -units : units; }
    }
    /** Motivo di esclusione, per simulatore e log. */
    public record Skipped(String campaignId, String reason) {}
    public record Result(List<Outcome> outcomes, List<Skipped> skipped) {}

    private final ExpressionEngine expressions;
    public CampaignEvaluator(ExpressionEngine expressions) { this.expressions = expressions; }

    public Result evaluate(RewardingAction action, List<Campaign> candidates, MemberContext member, Map<String, Usage> usageByCampaign, Instant processedAt) {
        Instant at = action.occurredAt() == null ? processedAt : action.occurredAt();
        Map<String, Object> attrs = action.attributes() == null ? Map.of() : action.attributes();
        List<TransactionLine> lines = TransactionLine.fromAttribute(attrs.get(EventTypes.ATTR_LINES));
        List<Outcome> out = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Campaign> ordered = candidates.stream().sorted(Comparator.comparing((Campaign c) -> c.startsAt() == null ? Instant.MIN : c.startsAt()).thenComparingInt(Campaign::displayOrder)).toList();
        for (Campaign c : ordered) {
            if (!c.isActiveAt(at)) { skipped.add(new Skipped(c.id(), "NOT_ACTIVE")); continue; }
            if (!triggerMatches(c.trigger(), action)) continue;
            Usage usage = usageByCampaign == null ? Usage.NONE : usageByCampaign.getOrDefault(c.id(), Usage.NONE);
            if (c.limits().perMemberTriggers() > 0 && usage.memberTriggersInPeriod() >= c.limits().perMemberTriggers()) { skipped.add(new Skipped(c.id(), "MEMBER_TRIGGER_LIMIT")); continue; }
            List<TransactionLine> filtered = filterLines(lines, c.trigger().lineFilter());
            if (c.trigger().lineFilter() != null && !lines.isEmpty() && filtered.isEmpty()) { skipped.add(new Skipped(c.id(), "NO_MATCHING_LINES")); continue; }
            Map<String, Object> ctx = context(action, attrs, filtered, member, processedAt);
            long unitsThisCampaign = 0;
            boolean fired = false;
            for (Campaign.Rule r : c.rules()) {
                if (!conditionsHold(r.conditions(), attrs, ctx)) continue;
                for (Campaign.Effect e : r.effects()) {
                    Outcome o = resolve(c, r, e, attrs, filtered, ctx, member);
                    if (o == null) continue;
                    if (o.isUnits() && o.type() == Campaign.Effect.Type.ADD_UNITS) {
                        long units = o.units();
                        if (c.limits().perMemberUnits() > 0) units = Math.min(units, Math.max(0, c.limits().perMemberUnits() - usage.memberUnitsInPeriod() - unitsThisCampaign));
                        if (c.limits().globalUnits() > 0) units = Math.min(units, Math.max(0, c.limits().globalUnits() - usage.campaignUnitsTotal() - unitsThisCampaign));
                        if (units <= 0) { skipped.add(new Skipped(c.id(), "UNITS_BUDGET")); continue; }
                        unitsThisCampaign += units;
                        o = new Outcome(o.campaignId(), o.version(), o.ruleId(), o.type(), o.wallet(), units, o.reference(), o.params(), o.expiresAt(), o.pendingUntil());
                    }
                    out.add(o);
                    fired = true;
                }
            }
            if (!fired) skipped.add(new Skipped(c.id(), "NO_RULE_MATCHED"));
        }
        return new Result(out, skipped);
    }

    static boolean triggerMatches(Campaign.Trigger t, RewardingAction a) {
        return switch (t.type()) {
            case PURCHASE_TRANSACTION -> EventTypes.ACTION_TRANSACTION.equals(a.actionType());
            case RETURN_TRANSACTION -> EventTypes.ACTION_TRANSACTION_RETURNED.equals(a.actionType()) || (EventTypes.ACTION_TRANSACTION.equals(a.actionType()) && a.isReversal());
            case REDEMPTION_CODE -> EventTypes.ACTION_CODE_REDEEMED.equals(a.actionType()) && (t.reference() == null || t.reference().equals(String.valueOf(a.attributes() == null ? null : a.attributes().get("campaign"))));
            case ACHIEVEMENT -> "ACHIEVEMENT_COMPLETED".equals(a.actionType()) && (t.reference() == null || t.reference().equals(a.externalRef()));
            case INTERNAL_EVENT, CUSTOM_EVENT -> t.matchesActionType(a.actionType());
            case SCHEDULE -> "SCHEDULE_TICK".equals(a.actionType()) && (t.reference() == null || t.reference().equals(a.externalRef()));
        };
    }

    static List<TransactionLine> filterLines(List<TransactionLine> lines, Campaign.LineFilter f) {
        if (f == null) return lines;
        return lines.stream().filter(l -> {
            if (f.includeSkus() != null && !f.includeSkus().isEmpty() && (l.sku() == null || !f.includeSkus().contains(l.sku()))) return false;
            if (f.excludeSkus() != null && l.sku() != null && f.excludeSkus().contains(l.sku())) return false;
            if (f.includeLabels() != null && !f.includeLabels().isEmpty() && f.includeLabels().stream().noneMatch(l::hasLabel)) return false;
            if (f.excludeLabels() != null && f.excludeLabels().stream().anyMatch(l::hasLabel)) return false;
            if (f.includeCategories() != null && !f.includeCategories().isEmpty() && (l.category() == null || !f.includeCategories().contains(l.category()))) return false;
            if (f.excludeCategories() != null && l.category() != null && f.excludeCategories().contains(l.category())) return false;
            if (f.includeBrands() != null && !f.includeBrands().isEmpty() && (l.brand() == null || !f.includeBrands().contains(l.brand()))) return false;
            return true;
        }).toList();
    }

    Map<String, Object> context(RewardingAction action, Map<String, Object> attrs, List<TransactionLine> lines, MemberContext m, Instant processedAt) {
        Map<String, Object> ctx = new HashMap<>();
        Map<String, Object> tx = new HashMap<>(attrs);
        tx.put("occurredAt", action.occurredAt());
        tx.put("actionType", action.actionType());
        tx.put("externalRef", action.externalRef());
        tx.put("grossValue", sum(lines.isEmpty() ? null : lines, attrs));
        tx.put("qty", lines.stream().map(l -> l.quantity() == null ? BigDecimal.ONE : l.quantity()).reduce(BigDecimal.ZERO, BigDecimal::add));
        ctx.put("transaction", tx);
        ctx.put("event", tx);
        ctx.put("lines", lines);
        Map<String, Object> member = new HashMap<>(m.attributes() == null ? Map.of() : m.attributes());
        member.put("id", m.memberId()); member.put("tier", m.tier()); member.put("segments", m.segments()); member.put("badges", m.badges()); member.put("enrolledAt", m.enrolledAt()); member.put("referrerId", m.referrerId());
        ctx.put("member", member);
        ctx.put("wallets", m.wallets() == null ? Map.of() : m.wallets());
        ctx.put("executionContext", Map.of("processedAt", processedAt));
        return ctx;
    }

    private static BigDecimal sum(List<TransactionLine> lines, Map<String, Object> attrs) {
        if (lines != null) return lines.stream().map(TransactionLine::amountOrZero).reduce(BigDecimal.ZERO, BigDecimal::add);
        Object v = attrs.get(EventTypes.ATTR_AMOUNT_EUR);
        try { return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    boolean conditionsHold(List<Campaign.Condition> conditions, Map<String, Object> attrs, Map<String, Object> ctx) {
        if (conditions == null) return true;
        for (Campaign.Condition c : conditions) {
            if (c.isExpression()) { if (!expressions.test(c.expression(), ctx)) return false; }
            else if (!io.loyaltyhub.rulesengine.domain.RuleEvaluatorBridge.test(new Rule.Condition(c.attribute(), c.op(), c.value()), attrs)) return false;
        }
        return true;
    }

    Outcome resolve(Campaign c, Campaign.Rule r, Campaign.Effect e, Map<String, Object> attrs, List<TransactionLine> lines, Map<String, Object> ctx, MemberContext m) {
        long units = 0;
        if (e.type() == Campaign.Effect.Type.ADD_UNITS || e.type() == Campaign.Effect.Type.DEDUCT_UNITS) {
            BigDecimal v = BigDecimal.ZERO;
            if (e.fixed() != null) v = v.add(e.fixed());
            if (e.perEur() != null) v = v.add(sum(lines.isEmpty() ? null : lines, attrs).multiply(e.perEur()));
            if (e.valueExpression() != null) v = v.add(expressions.number(e.valueExpression(), ctx));
            units = v.setScale(0, RoundingMode.HALF_UP).longValue();
            if (units == 0) return null;
        }
        Instant expires = e.expiresAtExpression() == null ? null : asInstant(expressions.eval(e.expiresAtExpression(), ctx));
        Instant pending = e.pendingUntilExpression() == null ? null : asInstant(expressions.eval(e.pendingUntilExpression(), ctx));
        return new Outcome(c.id(), c.version(), r.id(), e.type(), e.wallet(), Math.abs(units), e.reference(), e.params(), expires, pending);
    }

    private static Instant asInstant(Object o) { return o == null ? null : o instanceof Instant i ? i : Instant.parse(o.toString()); }
}
