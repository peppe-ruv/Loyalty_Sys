package it.iren.loyalty.engagementservice.domain;

import java.util.List;

/** Porte verso il backoffice/CMS: definizioni pubblicate di achievement, challenge, badge e classifiche. */
public interface Definitions {
    List<Achievement> achievements();
    List<Challenge> challenges();
    List<Badge> badges();
    List<Leaderboard> leaderboards();
}
