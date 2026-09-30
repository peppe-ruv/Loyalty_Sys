package io.loyaltyhub.engagement;

/**
 * Il portale di engagement (inbox, pop-up, contenuti, tema) con il membro solo dal token, con token OIDC veri (F2-SEC-09,
 * ADR-048, Q-410, Q-553, Q-554): gli scenari sono in {@link PortalOidcScenarios} (righe TB-ENG-MBP-020…037), condivisi
 * con {@code TestbookEngMemberPrincipalIT}, che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class EngagementPortalOidcIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 910000;
    }
}
