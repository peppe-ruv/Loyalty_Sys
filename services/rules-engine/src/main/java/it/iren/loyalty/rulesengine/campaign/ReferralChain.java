package it.iren.loyalty.rulesengine.campaign;

import java.util.ArrayList;
import java.util.List;

/**
 * Campagne referral multilivello (RF-85): l'azione del presentato premia il presentatore (livello 1), il presentatore
 * del presentatore (livello 2) e così via, con effetti diversi per livello. La catena è risolta da member-service
 * ({@code referredBy} ricorsivo, al massimo {@code maxLevels}); qui si decide chi riceve quale effetto.
 */
public final class ReferralChain {
    private ReferralChain() {}

    /** Effetti per livello: params {@code level} = 1..n sull'effetto; senza parametro = tutti i livelli. */
    public record Grant(String beneficiaryMemberId, int level, CampaignEvaluator.Outcome outcome) {}

    public static List<Grant> distribute(List<String> chainReferrers, List<CampaignEvaluator.Outcome> outcomes, int maxLevels) {
        List<Grant> grants = new ArrayList<>();
        for (int i = 0; i < Math.min(chainReferrers.size(), maxLevels); i++) {
            int level = i + 1;
            for (CampaignEvaluator.Outcome o : outcomes) {
                String lvl = o.params() == null ? null : o.params().get("level");
                if (lvl == null || Integer.parseInt(lvl) == level) grants.add(new Grant(chainReferrers.get(i), level, o));
            }
        }
        return grants;
    }
}
