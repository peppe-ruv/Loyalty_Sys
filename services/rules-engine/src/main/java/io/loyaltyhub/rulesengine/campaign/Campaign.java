package io.loyaltyhub.rulesengine.campaign;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Campagna (RF-80..RF-86): trigger, fino a 6 regole con al più
 * 30 condizioni ciascuna, effetti, limiti e budget, visibilità, attributi custom, unità con scadenza/sospensione
 * calcolate da formula. Versionata e immutabile come le regole (RF-06). {@link io.loyaltyhub.rulesengine.domain.Rule}
 * resta la forma semplice: {@link #fromRule} la converte in campagna.
 */
public record Campaign(
        String id, String version, String name, Kind kind,
        Trigger trigger,
        Instant startsAt, Instant endsAt, boolean active,
        Visibility visibility,
        List<Rule> rules,
        Limits limits,
        /** ordine di esecuzione fra campagne concorrenti; a parità, l'ordine di pubblicazione */
        int displayOrder,
        Map<String, String> customAttributes
) {
    public static final int MAX_RULES = 6, MAX_CONDITIONS = 30;

    /** DIRECT premia chi compie l'azione; REFERRAL premia la catena dei presentatori per le azioni del presentato (RF-85). */
    public enum Kind { DIRECT, REFERRAL, AUTOMATION }

    /**
     * Trigger (RF-80): tipo azione canonica; per CUSTOM_EVENT il tipo è quello dello schema (RF-98); per ACHIEVEMENT l'id;
     * per REDEMPTION_CODE la campagna codici; INTERNAL_EVENT = azioni interne (TIER_CHANGED, MEMBER_ENROLLED, ACHIEVEMENT_PROGRESSED...).
     */
    public record Trigger(Type type, String actionType, String reference, LineFilter lineFilter) {
        public enum Type { PURCHASE_TRANSACTION, RETURN_TRANSACTION, INTERNAL_EVENT, CUSTOM_EVENT, ACHIEVEMENT, REDEMPTION_CODE, SCHEDULE }
        public boolean matchesActionType(String t) { return actionType == null || actionType.equals(t); }
    }

    /** Filtro sulle righe della transazione (RF-62): riusa quello delle regole semplici. */
    public record LineFilter(Set<String> includeSkus, Set<String> excludeSkus, Set<String> includeLabels, Set<String> excludeLabels, Set<String> excludeCategories, Set<String> includeCategories, Set<String> includeBrands, String skuCollection) {}

    /** Visibilità nel sito (RF-82): EVERYONE, SEGMENTS/TIERS elencati, HIDDEN; non limita l'esecuzione, solo la vista. */
    public record Visibility(Mode mode, Set<String> segments, Set<String> tiers) {
        public enum Mode { EVERYONE, SEGMENTS, TIERS, HIDDEN }
        public static final Visibility EVERYONE = new Visibility(Mode.EVERYONE, Set.of(), Set.of());
    }

    /** Regola: tutte le condizioni devono valere; le regole sono indipendenti tra loro. */
    public record Rule(String id, List<Condition> conditions, List<Effect> effects) {}

    /**
     * Condizione: attributo + operatore + valore (forma dichiarativa, come {@link io.loyaltyhub.rulesengine.domain.Rule.Condition})
     * oppure espressione libera sul contesto (RF-84), es. {@code #transaction['amountEur'] > 50 and #member['tier'] == 'TOP'}.
     */
    public record Condition(String attribute, io.loyaltyhub.rulesengine.domain.Rule.Operator op, String value, String expression) {
        public static Condition expr(String e) { return new Condition(null, null, null, e); }
        public static Condition of(String a, io.loyaltyhub.rulesengine.domain.Rule.Operator op, String v) { return new Condition(a, op, v, null); }
        public boolean isExpression() { return expression != null && !expression.isBlank(); }
    }

    /**
     * Effetto (RF-81): ADD_UNITS/DEDUCT_UNITS su un wallet con valore fisso, proporzionale (per euro) o formula; GIVE_REWARD;
     * SET_ATTRIBUTE/REMOVE_ATTRIBUTE sul membro; GRANT_BADGE; ASSIGN_TIER; EMIT_EVENT (azione interna per campagne a catena).
     * {@code expiresAtExpression} e {@code pendingUntilExpression} calcolano scadenza e sospensione (RF-83), es.
     * {@code #fn.add_days_to_date(#transaction['occurredAt'], 30)}; se assenti valgono le impostazioni del wallet.
     */
    public record Effect(Type type, String wallet, java.math.BigDecimal fixed, java.math.BigDecimal perEur, String valueExpression,
                         String reference, String expiresAtExpression, String pendingUntilExpression, Map<String, String> params) {
        public enum Type { ADD_UNITS, DEDUCT_UNITS, GIVE_REWARD, SET_ATTRIBUTE, REMOVE_ATTRIBUTE, GRANT_BADGE, ASSIGN_TIER, EMIT_EVENT }
        public static Effect addUnits(String wallet, long fixed) { return new Effect(Type.ADD_UNITS, wallet, java.math.BigDecimal.valueOf(fixed), null, null, null, null, null, Map.of()); }
        public static Effect addUnitsPerEur(String wallet, java.math.BigDecimal perEur) { return new Effect(Type.ADD_UNITS, wallet, null, perEur, null, null, null, null, Map.of()); }
        public static Effect addUnitsExpr(String wallet, String expr) { return new Effect(Type.ADD_UNITS, wallet, null, null, expr, null, null, null, Map.of()); }
        public static Effect giveReward(String rewardId) { return new Effect(Type.GIVE_REWARD, null, null, null, null, rewardId, null, null, Map.of()); }
        public static Effect grantBadge(String badgeCode) { return new Effect(Type.GRANT_BADGE, null, null, null, null, badgeCode, null, null, Map.of()); }
        public static Effect setAttribute(String key, String value) { return new Effect(Type.SET_ATTRIBUTE, null, null, null, null, key, null, null, Map.of("value", value)); }
        public static Effect assignTier(String tierCode) { return new Effect(Type.ASSIGN_TIER, null, null, null, null, tierCode, null, null, Map.of()); }
    }

    /**
     * Limiti e budget (RF-82): esecuzioni per membro nel periodo, unità totali della campagna, unità per membro nel periodo.
     * Il periodo è calcolato sulla data di business dell'evento; gli storni restituiscono il budget.
     */
    public record Limits(int perMemberTriggers, Period perMemberTriggersPeriod, long globalUnits, long perMemberUnits, Period perMemberUnitsPeriod) {
        public enum Period { NONE, HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY, TOTAL }
        public static final Limits NONE = new Limits(0, Period.NONE, 0, 0, Period.NONE);
    }

    public boolean isActiveAt(Instant t) {
        return active && (startsAt == null || !t.isBefore(startsAt)) && (endsAt == null || t.isBefore(endsAt));
    }

    public Campaign {
        if (rules != null && rules.size() > MAX_RULES) throw new IllegalArgumentException("max " + MAX_RULES + " rules");
        if (rules != null) for (Rule r : rules) if (r.conditions() != null && r.conditions().size() > MAX_CONDITIONS) throw new IllegalArgumentException("max " + MAX_CONDITIONS + " conditions");
        if (limits == null) limits = Limits.NONE;
        if (visibility == null) visibility = Visibility.EVERYONE;
        if (customAttributes == null) customAttributes = Map.of();
    }

    /** Conversione della regola semplice (RF-05, RF-63..RF-66) nel modello campagna. */
    public static Campaign fromRule(io.loyaltyhub.rulesengine.domain.Rule r) {
        var conds = r.conditions() == null ? List.<Condition>of() : r.conditions().stream().map(c -> Condition.of(c.attribute(), c.op(), c.value())).toList();
        var effects = new java.util.ArrayList<Effect>();
        if (r.rewardPoints() != 0) effects.add(Effect.addUnits("PREMIO", r.rewardPoints()));
        if (r.earning().pointsPerEurOrZero().signum() != 0) effects.add(Effect.addUnitsPerEur("PREMIO", r.earning().pointsPerEurOrZero()));
        if (r.statusPoints() != 0) effects.add(Effect.addUnits("STATUS", r.statusPoints()));
        if (r.earning().autoRewardId() != null) effects.add(Effect.giveReward(r.earning().autoRewardId()));
        var lf = r.earning().lineFilter();
        Trigger.Type tt = "TRANSACTION".equals(r.actionType()) ? Trigger.Type.PURCHASE_TRANSACTION : "TRANSACTION_RETURNED".equals(r.actionType()) ? Trigger.Type.RETURN_TRANSACTION : Trigger.Type.CUSTOM_EVENT;
        return new Campaign(r.id(), r.version(), r.id(), Kind.DIRECT,
                new Trigger(tt, r.actionType(), null, lf == null ? null : new LineFilter(lf.includeSkus(), lf.excludeSkus(), lf.includeLabels(), lf.excludeLabels(), lf.excludeCategories(), Set.of(), Set.of(), null)),
                r.validFrom(), r.validTo(), true, Visibility.EVERYONE, List.of(new Rule(r.id() + "-r1", conds, effects)),
                new Limits(r.limits().maxUsesPerMemberPerPeriod(), Limits.Period.TOTAL, 0, r.capPerMemberPerPeriod(), Limits.Period.TOTAL), 0, Map.of());
    }
}
