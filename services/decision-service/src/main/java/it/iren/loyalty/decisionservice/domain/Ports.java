package it.iren.loyalty.decisionservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Porte del motore decisionale verso il backoffice (configurazione) e verso gli altri servizi (dati). Le implementazioni
 * HTTP stanno in {@code DecisionConfig}; nei test e in locale ci sono i seed in memoria.
 */
public final class Ports {
    private Ports() {}

    /** Policy pubblicate nel backoffice (collezione {@code decision-policies}); {@code current()} è quella di default attiva. */
    public interface PolicySource {
        DecisionPolicy current();
        Optional<DecisionPolicy> byId(String id);
    }

    /** Catalogo offerte pubblicate (collezione {@code offers}). */
    public interface OfferSource {
        List<Offer> activeOffers(Instant now);
    }

    /** Esperimenti attivi (collezione {@code experiments}). */
    public interface ExperimentSource {
        List<Experiment> active(Instant now);
    }

    /** Routing delle previsioni (collezione {@code prediction-providers}). */
    public interface PredictionConfigSource {
        PredictionRouting routing();
    }

    /** Customer 360 dal context-service (RF-125): il motore non legge i database degli altri servizi. */
    public interface ContextSource {
        DecisionContext load(String memberId);
    }

    /** Valutazione delle campagne senza effetti collaterali (rules-engine {@code POST /v1/evaluations}). */
    public interface CampaignEvaluations {
        record Outcome(String campaignId, String version, String ruleId, String type, String wallet, long units, String reference,
                       java.util.Map<String, String> params, Instant expiresAt, Instant pendingUntil) {}
        record Skipped(String campaignId, String reason) {}
        record Result(List<Outcome> outcomes, List<Skipped> skipped) {}
        Result evaluate(String memberId, it.iren.loyalty.common.event.RewardingAction action);
    }

    /** Condizione di eligibilità delle offerte (SpEL sul contesto). */
    public interface OfferCondition {
        boolean test(String expression, DecisionContext ctx, java.util.Map<String, Object> event);
    }
}
