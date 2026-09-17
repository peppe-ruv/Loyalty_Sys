package io.loyaltyhub.decisionservice.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Policy decisionale (RF-127), configurata nel backoffice (collezione {@code decision-policies}) e versionata.
 * Contiene il catalogo delle azioni con priorità, valore, costo e limiti; i vincoli (consensi, pressione commerciale,
 * ore di silenzio, rischio, budget); la strategia di punteggio (priorità, pesi o formula); l'elenco delle azioni
 * "sempre applicate" (effetti contrattuali delle campagne: punti, tier, badge) rispetto a quelle "arbitrate"
 * (premi, coupon, messaggi, offerte, campagne, richieste di feedback), di cui al più {@code maxArbitratedPerEvent}.
 */
public record DecisionPolicy(
        String id, String version, boolean active,
        Map<ActionType, ActionSpec> actions,
        Constraints constraints,
        Scoring scoring,
        Set<ActionType> alwaysApply,
        int maxArbitratedPerEvent,
        List<String> channelPreferenceOrder
) {
    public enum ActionType { NO_ACTION, AWARD_POINTS, ISSUE_REWARD, ISSUE_COUPON, UPGRADE_TIER, GRANT_BADGE, SET_ATTRIBUTE, EMIT_EVENT, SEND_MESSAGE, SHOW_OFFER, TRIGGER_CAMPAIGN, ASK_FOR_FEEDBACK }
    public enum Period { HOUR, DAY, WEEK, MONTH }

    /**
     * @param baseValue valore di business dell'azione (unità arbitrarie, es. margine atteso)
     * @param cost costo stimato (es. punti o euro del premio, costo contatto)
     * @param requiredConsents finalità di consenso necessarie (es. marketing)
     * @param maxRiskLevel livello di rischio massimo ammesso (LOW, MEDIUM, HIGH, CRITICAL)
     * @param maxPerMemberPerPeriod limite di esecuzioni per membro (0 = nessuno) nel periodo; cooldownHours tra due esecuzioni
     */
    public record ActionSpec(boolean enabled, int priority, double baseValue, double cost, Set<String> requiredConsents, String maxRiskLevel,
                             int maxPerMemberPerPeriod, Period period, int cooldownHours, Set<String> channels) {}

    /**
     * @param contactCap7dByChannel contatti massimi in 7 giorni per canale (pressione commerciale)
     * @param quietHoursFrom/To ore locali in cui non si inviano messaggi (es. 21-8)
     * @param dailyUnitsBudget budget giornaliero di unità per le azioni arbitrate (0 = nessuno)
     * @param suppressionSegments segmenti esclusi da ogni azione arbitrata (es. contenziosi, opt-out)
     * @param blockRiskLevel a partire da questo livello di rischio solo NO_ACTION (es. CRITICAL)
     */
    public record Constraints(Map<String, Integer> contactCap7dByChannel, Integer quietHoursFrom, Integer quietHoursTo, long dailyUnitsBudget,
                              Set<String> suppressionSegments, String blockRiskLevel, int minHoursBetweenOffers) {}

    /**
     * Strategia: PRIORITY (ordine per priorità dell'azione), WEIGHTED (punteggio = pesi × fattori), EXPRESSION (formula
     * SpEL sul contesto, es. {@code #value * (1 + #predictions['offerPropensity']) - #cost}).
     */
    public record Scoring(Strategy strategy, double valueWeight, double costWeight, double propensityWeight, double churnWeight, double recencyWeight,
                          Map<String, Double> tierBoost, double channelPreferenceBonus, String expression) {
        public enum Strategy { PRIORITY, WEIGHTED, EXPRESSION }
    }

    public static Set<ActionType> defaultAlwaysApply() { return Set.of(ActionType.AWARD_POINTS, ActionType.UPGRADE_TIER, ActionType.GRANT_BADGE, ActionType.SET_ATTRIBUTE, ActionType.EMIT_EVENT); }

    public static DecisionPolicy example() {
        Map<ActionType, ActionSpec> a = new java.util.EnumMap<>(ActionType.class);
        a.put(ActionType.AWARD_POINTS, new ActionSpec(true, 100, 1, 0, Set.of(), "CRITICAL", 0, Period.DAY, 0, Set.of()));
        a.put(ActionType.UPGRADE_TIER, new ActionSpec(true, 100, 1, 0, Set.of(), "CRITICAL", 0, Period.DAY, 0, Set.of()));
        a.put(ActionType.GRANT_BADGE, new ActionSpec(true, 100, 1, 0, Set.of(), "CRITICAL", 0, Period.DAY, 0, Set.of()));
        a.put(ActionType.SET_ATTRIBUTE, new ActionSpec(true, 100, 0, 0, Set.of(), "CRITICAL", 0, Period.DAY, 0, Set.of()));
        a.put(ActionType.EMIT_EVENT, new ActionSpec(true, 100, 0, 0, Set.of(), "CRITICAL", 0, Period.DAY, 0, Set.of()));
        a.put(ActionType.ISSUE_REWARD, new ActionSpec(true, 80, 10, 5, Set.of(), "MEDIUM", 3, Period.MONTH, 24, Set.of("app", "web", "email")));
        a.put(ActionType.ISSUE_COUPON, new ActionSpec(true, 70, 8, 3, Set.of("marketing"), "MEDIUM", 4, Period.MONTH, 24, Set.of("app", "web", "email", "sms")));
        a.put(ActionType.SHOW_OFFER, new ActionSpec(true, 60, 6, 0.5, Set.of("marketing"), "HIGH", 10, Period.WEEK, 6, Set.of("app", "web")));
        a.put(ActionType.SEND_MESSAGE, new ActionSpec(true, 40, 3, 0.2, Set.of("marketing"), "HIGH", 3, Period.WEEK, 24, Set.of("push", "email", "sms", "app")));
        a.put(ActionType.TRIGGER_CAMPAIGN, new ActionSpec(true, 50, 5, 1, Set.of(), "MEDIUM", 1, Period.MONTH, 72, Set.of()));
        a.put(ActionType.ASK_FOR_FEEDBACK, new ActionSpec(true, 20, 2, 0.1, Set.of("marketing"), "HIGH", 1, Period.MONTH, 168, Set.of("app", "email")));
        return new DecisionPolicy("default", "1", true, a,
                new Constraints(Map.of("push", 3, "email", 2, "sms", 1, "app", 7), 21, 8, 0, Set.of("contenziosi"), "CRITICAL", 6),
                new Scoring(Scoring.Strategy.WEIGHTED, 1.0, 0.8, 2.0, 1.5, 0.5, Map.of("PLUS", 1.1, "TOP", 1.25), 1.0, null),
                defaultAlwaysApply(), 1, List.of("app", "push", "email", "web", "sms"));
    }
}
