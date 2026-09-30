package io.loyaltyhub.campaign;

/**
 * L'elenco «Guadagna» del portale con il membro solo dal token, con token OIDC veri (F2-SEC-09, ADR-048, Q-410, Q-553,
 * Q-554, Q-560): gli scenari sono in {@link PortalOidcScenarios} (righe TB-CMP-MBP-020…034), condivisi con
 * {@code TestbookCmpMemberPrincipalIT}, che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class CampaignPortalOidcIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 960000;
    }
}
