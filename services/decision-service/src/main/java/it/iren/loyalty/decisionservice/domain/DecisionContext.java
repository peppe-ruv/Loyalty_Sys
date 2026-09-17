package it.iren.loyalty.decisionservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Vista del cliente usata dal motore decisionale: sottoinsieme del Customer 360 (context-service, RF-125) più le
 * decisioni recenti (decision log) e le previsioni (RF-130). Il motore non legge mai i database degli altri servizi.
 *
 * @param walletActive saldi attivi per wallet
 * @param consents finalità di consenso → concesso
 * @param riskLevel LOW, MEDIUM, HIGH, CRITICAL (fraud-service, RF-131) — null se non valutato
 * @param recencyDays giorni dall'ultima transazione (RFM) — null se mai transato
 * @param contacts7dByChannel contatti ricevuti negli ultimi 7 giorni per canale (pressione commerciale)
 * @param priorActions azioni arbitrate già eseguite per il membro (per limiti di periodo e cooldown)
 * @param activity riscatti, offerte presentate/accettate, conteggi azioni, spesa per categoria (per le previsioni a regole)
 * @param predictions chiave → valore in [0,1] (churnRisk, purchasePropensity, rewardAcceptance, offerPropensity, engagement...)
 */
public record DecisionContext(String memberId, String tier, List<String> segments, Map<String, Long> walletActive,
                              Map<String, Boolean> consents, String riskLevel, Integer recencyDays, int frequency90d, double monetary365d,
                              String preferredChannel, Map<String, Integer> contacts7dByChannel, List<PriorAction> priorActions,
                              Activity activity, Map<String, Double> predictions) {

    public record PriorAction(DecisionPolicy.ActionType action, String reference, Instant at) {}

    public record Activity(int redemptions90d, int offersPresented90d, int offersAccepted90d, Map<String, Integer> actionCounts30d,
                           Map<String, Double> categorySpend365d, Instant enrolledAt, int badges) {
        public static final Activity NONE = new Activity(0, 0, 0, Map.of(), Map.of(), null, 0);
    }

    public static DecisionContext minimal(String memberId, String tier) {
        return new DecisionContext(memberId, tier, List.of(), Map.of(), Map.of(), null, null, 0, 0, null, Map.of(), List.of(), Activity.NONE, Map.of());
    }

    public DecisionContext withPredictions(Map<String, Double> p) {
        return new DecisionContext(memberId, tier, segments, walletActive, consents, riskLevel, recencyDays, frequency90d, monetary365d,
                preferredChannel, contacts7dByChannel, priorActions, activity, p == null ? Map.of() : p);
    }

    public DecisionContext withPriorActions(List<PriorAction> prior) {
        return new DecisionContext(memberId, tier, segments, walletActive, consents, riskLevel, recencyDays, frequency90d, monetary365d,
                preferredChannel, contacts7dByChannel, prior == null ? List.of() : prior, activity, predictions);
    }

    public Activity activityOrNone() { return activity == null ? Activity.NONE : activity; }
    public boolean consented(String purpose) { return consents != null && Boolean.TRUE.equals(consents.get(purpose)); }
    public double prediction(String key, double fallback) { Double v = predictions == null ? null : predictions.get(key); return v == null ? fallback : v; }
}
