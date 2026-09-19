package io.loyaltyhub.campaign.api;

/** Voce "come guadagnare" del portale (docs/servizi/campaign-service.md §3). */
public record PortalCampaignView(
        String code,
        String name,
        String memberDescription,
        String icon,
        String rewardSummary,
        String endsAt
) {
}
