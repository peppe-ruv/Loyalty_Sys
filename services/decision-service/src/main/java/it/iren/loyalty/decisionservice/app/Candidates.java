package it.iren.loyalty.decisionservice.app;

import it.iren.loyalty.decisionservice.domain.Candidate;
import it.iren.loyalty.decisionservice.domain.DecisionContext;
import it.iren.loyalty.decisionservice.domain.DecisionPolicy.ActionType;
import it.iren.loyalty.decisionservice.domain.Offer;
import it.iren.loyalty.decisionservice.domain.Ports;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Passo di eligibilità (RF-127): costruisce i candidati dagli effetti delle campagne (rules-engine, senza effetti
 * collaterali) e dalle offerte del catalogo la cui condizione è vera nel contesto.
 */
public final class Candidates {
    private Candidates() {}

    /** Effetto di campagna → candidato. ADD/DEDUCT_UNITS restano "contrattuali" (AWARD_POINTS, sempre applicati). */
    public static List<Candidate> fromCampaigns(Ports.CampaignEvaluations.Result result) {
        List<Candidate> out = new ArrayList<>();
        if (result == null || result.outcomes() == null) return out;
        for (var o : result.outcomes()) {
            Map<String, Object> params = new HashMap<>(o.params() == null ? Map.of() : o.params());
            params.put("ruleId", o.ruleId());
            if (o.expiresAt() != null) params.put("expiresAt", o.expiresAt().toString());
            if (o.pendingUntil() != null) params.put("pendingUntil", o.pendingUntil().toString());
            ActionType type;
            long units = 0;
            switch (o.type()) {
                case "ADD_UNITS" -> { type = ActionType.AWARD_POINTS; units = o.units(); }
                case "DEDUCT_UNITS" -> { type = ActionType.AWARD_POINTS; units = -o.units(); }
                case "GIVE_REWARD" -> type = ActionType.ISSUE_REWARD;
                case "GRANT_BADGE" -> type = ActionType.GRANT_BADGE;
                case "ASSIGN_TIER" -> type = ActionType.UPGRADE_TIER;
                case "SET_ATTRIBUTE" -> type = ActionType.SET_ATTRIBUTE;
                case "REMOVE_ATTRIBUTE" -> { type = ActionType.SET_ATTRIBUTE; params.put("value", null); }
                case "EMIT_EVENT" -> type = ActionType.EMIT_EVENT;
                case "SEND_MESSAGE" -> type = ActionType.SEND_MESSAGE;
                case "SHOW_OFFER" -> type = ActionType.SHOW_OFFER;
                case "ISSUE_COUPON" -> type = ActionType.ISSUE_COUPON;
                default -> { continue; }
            }
            out.add(new Candidate(type, "campaign", o.campaignId() + "@" + o.version(), o.reference(), o.wallet(), units, null, null, null, params));
        }
        return out;
    }

    /** Offerte del catalogo attive la cui condizione è vera. */
    public static List<Candidate> fromOffers(List<Offer> offers, DecisionContext ctx, Map<String, Object> event, Ports.OfferCondition condition, Instant now) {
        List<Candidate> out = new ArrayList<>();
        if (offers == null) return out;
        for (Offer o : offers) {
            if (!o.isActiveAt(now)) continue;
            if (condition != null && !condition.test(o.conditionExpression(), ctx, event)) continue;
            out.add(o.toCandidate());
        }
        return out;
    }
}
