package io.loyaltyhub.campaign.domain;

/** Ciclo di vita di una campagna (docs/03 §3.6, docs/06 §7). */
public enum CampaignStatus {
    DRAFT,
    IN_REVIEW,
    LIVE,
    PAUSED,
    ENDED,
    ARCHIVED
}
