package io.loyaltyhub.campaign.api;

/**
 * Voce "come guadagnare" del portale (docs/servizi/campaign-service.md §3). {@code memberLimit} è il primo limite per
 * membro, se c'è (es. PT-11: "3 di 10 inviti premiati in questa edizione").
 */
public record PortalCampaignView(
        String code,
        String name,
        String memberDescription,
        String icon,
        String rewardSummary,
        String endsAt,
        MemberLimit memberLimit
) {
    public record MemberLimit(int max, String period) {
    }
}
