package io.loyaltyhub.campaign.engine;

import java.util.List;

/**
 * Esito completo della valutazione di un'azione (docs/03 §3.5, docs/servizi/campaign-service.md §2).
 * {@code results} è la spiegabilità per campagna (registro/simulazione); {@code effects} sono gli effetti
 * da pubblicare su {@code lh.effects.v1}.
 */
public record Evaluation(Outcome outcome, List<CampaignResult> results, List<GrantedEffect> effects) {

    public enum Outcome {
        MATCHED, NO_MATCH, NO_MEMBER
    }

    /** Motivi di scarto di una campagna (docs/03 §3.5). */
    public enum SkipReason {
        NOT_IN_SCHEDULE, AUDIENCE, CONDITION, EXCLUSIVE, LIMIT, BUDGET, EFFECT_NOT_SUPPORTED_YET
    }

    /** Riga di spiegabilità per campagna. */
    public record CampaignResult(
            String campaignCode,
            String campaignName,
            boolean matched,
            SkipReason reason,
            List<FailedCondition> failedConditions,
            List<EffectResult> effects
    ) {
        public static CampaignResult matched(String code, String name, List<EffectResult> effects) {
            return new CampaignResult(code, name, true, null, List.of(), effects);
        }

        public static CampaignResult skipped(String code, String name, SkipReason reason) {
            return new CampaignResult(code, name, false, reason, List.of(), List.of());
        }

        public static CampaignResult skippedConditions(String code, String name, List<FailedCondition> failed) {
            return new CampaignResult(code, name, false, SkipReason.CONDITION, failed, List.of());
        }
    }

    /** Foglia di condizione fallita (spiegabilità del filtro). */
    public record FailedCondition(String field, String cmp, Object value, Object actual) {
    }

    /** Effetto sintetico per la spiegabilità e il fatto {@code campaign.evaluated}. */
    public record EffectResult(String type, String currency, Long amount) {
    }
}
