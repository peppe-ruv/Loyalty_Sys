package it.iren.loyalty.rulesengine.client;

import java.util.Set;

/** Porta verso engagement-service e member-service per gli effetti non monetari delle campagne (badge, attributi, eventi). */
public interface EngagementClient {
    Set<String> badgesOf(String memberId);
    void grantBadge(String memberId, String badgeCode, String grantKey);
    void setAttribute(String memberId, String key, String value);
    void emit(String memberId, String actionType, String key);
}
