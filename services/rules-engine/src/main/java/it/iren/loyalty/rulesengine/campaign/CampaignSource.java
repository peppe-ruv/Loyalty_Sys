package it.iren.loyalty.rulesengine.campaign;

import java.util.List;

/** Porta verso il backoffice: campagne pubblicate (dirette, referral, automazioni) per tipo azione. */
public interface CampaignSource {
    List<Campaign> publishedCampaignsFor(String actionType);
    List<Campaign> scheduled();
}
