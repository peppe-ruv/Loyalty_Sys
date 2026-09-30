package io.loyaltyhub.gamification;

/**
 * Il portale del gioco con il membro solo dal token, con token OIDC veri (F2-SEC-09, ADR-048, Q-410, Q-553, Q-554,
 * Q-559): gli scenari sono in {@link PortalOidcScenarios} (righe TB-GAM-MBP-020…035), condivisi con
 * {@code TestbookGamMemberPrincipalIT}, che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class GamificationPortalOidcIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 910000;
    }
}
