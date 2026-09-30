package io.loyaltyhub.reward;

/**
 * Il portale di reward-service con il membro solo dal token, con token OIDC veri (F2-SEC-09, ADR-048, Q-410, Q-553,
 * Q-554, Q-556): gli scenari sono in {@link PortalOidcScenarios} (righe TB-RWD-MBP-020…036), condivisi con
 * {@code TestbookRwdMemberPrincipalIT}, che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class RewardPortalOidcIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 910000;
    }
}
