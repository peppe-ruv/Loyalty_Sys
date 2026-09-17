package it.iren.loyalty.decisionservice.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provider a regole (RF-130, primo livello dell'evoluzione incrementale): previsioni deterministiche da RFM e
 * comportamento, senza modelli. Le soglie sono configurabili nel backoffice ({@link Thresholds}) e i risultati sono
 * spiegabili; quando arriverà un modello reale basterà instradare la chiave a un altro provider.
 */
public final class RuleBasedPredictionProvider implements PredictionProvider {
    public static final String NAME = "rule-based";

    /**
     * @param churnRecencyDays giorni di inattività oltre i quali il rischio di abbandono è massimo
     * @param activeFrequency90d transazioni in 90 giorni considerate "cliente attivo" (propensione all'acquisto = 1)
     * @param highValueEur spesa a 365 giorni considerata "alto valore"
     * @param baseRewardAcceptance accettazione premi di partenza per chi non ha mai riscattato
     */
    public record Thresholds(int churnRecencyDays, int activeFrequency90d, double highValueEur, double baseRewardAcceptance, double baseOfferPropensity) {
        public static final Thresholds DEFAULT = new Thresholds(120, 6, 1500, 0.35, 0.3);
    }

    private final Thresholds t;
    public RuleBasedPredictionProvider() { this(Thresholds.DEFAULT); }
    public RuleBasedPredictionProvider(Thresholds t) { this.t = t == null ? Thresholds.DEFAULT : t; }

    @Override public String name() { return NAME; }

    @Override
    public Map<String, Double> predict(DecisionContext ctx, Set<String> keys) {
        var a = ctx.activityOrNone();
        Map<String, Double> out = new HashMap<>();
        // churnRisk: cresce linearmente con la recency, azzerato dalla frequenza recente
        double churn = ctx.recencyDays() == null ? 0.5 : Math.min(1.0, ctx.recencyDays() / (double) t.churnRecencyDays());
        if (ctx.frequency90d() >= t.activeFrequency90d()) churn *= 0.5;
        out.put("churnRisk", PredictionProvider.clamp(churn));
        // purchasePropensity: frequenza a 90 giorni rispetto alla soglia di "attivo", penalizzata dalla recency
        double purchase = Math.min(1.0, ctx.frequency90d() / (double) t.activeFrequency90d()) * (1 - 0.5 * churn);
        out.put("purchasePropensity", PredictionProvider.clamp(purchase));
        // rewardAcceptance: chi riscatta accetta; di base la soglia configurata
        double reward = a.redemptions90d() > 0 ? Math.min(1.0, t.baseRewardAcceptance() + 0.2 * a.redemptions90d()) : t.baseRewardAcceptance();
        out.put("rewardAcceptance", PredictionProvider.clamp(reward));
        // offerPropensity: tasso di accettazione delle offerte presentate, altrimenti base
        double offer = a.offersPresented90d() > 0 ? a.offersAccepted90d() / (double) a.offersPresented90d() : t.baseOfferPropensity();
        out.put("offerPropensity", PredictionProvider.clamp(offer));
        // engagement: azioni non transazionali negli ultimi 30 giorni e badge
        int actions30d = a.actionCounts30d() == null ? 0 : a.actionCounts30d().values().stream().mapToInt(Integer::intValue).sum();
        out.put("engagement", PredictionProvider.clamp(Math.min(1.0, actions30d / 10.0) * 0.8 + Math.min(0.2, a.badges() * 0.05)));
        // customerValue: spesa a 365 giorni rispetto all'alto valore, con bonus tier
        double value = Math.min(1.0, ctx.monetary365d() / t.highValueEur());
        if ("TOP".equalsIgnoreCase(ctx.tier())) value = Math.max(value, 0.8); else if ("PLUS".equalsIgnoreCase(ctx.tier())) value = Math.max(value, 0.5);
        out.put("customerValue", PredictionProvider.clamp(value));
        // categoryAffinity:<cat> = quota di spesa della categoria
        if (a.categorySpend365d() != null && !a.categorySpend365d().isEmpty()) {
            double total = a.categorySpend365d().values().stream().mapToDouble(Double::doubleValue).sum();
            if (total > 0) a.categorySpend365d().forEach((cat, v) -> out.put("categoryAffinity:" + cat, PredictionProvider.clamp(v / total)));
        }
        if (keys != null && !keys.isEmpty()) out.keySet().retainAll(keys);
        return out;
    }
}
