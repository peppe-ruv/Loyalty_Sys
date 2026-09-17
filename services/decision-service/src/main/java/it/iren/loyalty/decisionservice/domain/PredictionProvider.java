package it.iren.loyalty.decisionservice.domain;

import java.util.Map;
import java.util.Set;

/**
 * Porta del livello AI/ML (RF-130): produce previsioni in [0,1] per chiave (churnRisk, purchasePropensity,
 * rewardAcceptance, offerPropensity, engagement, customerValue, categoryAffinity:&lt;cat&gt;).
 * Le implementazioni sono intercambiabili senza toccare il motore: {@link RuleBasedPredictionProvider} (RFM, nessun
 * modello), un modello locale (ONNX) o un servizio esterno ({@code HttpPredictionProvider}); il routing per chiave è
 * configurato nel backoffice ({@link PredictionRouting}). L'AI non modifica mai punti, saldi, denaro, eligibilità o
 * status: propone soltanto, e la decisione resta spiegabile e configurabile.
 */
public interface PredictionProvider {
    String name();
    Map<String, Double> predict(DecisionContext ctx, Set<String> keys);

    static double clamp(double v) { return Double.isNaN(v) ? 0 : Math.max(0.0, Math.min(1.0, v)); }
}
