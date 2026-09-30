package io.loyaltyhub.wallet;

/**
 * Il wallet del portale con il membro solo dal token, con token OIDC veri (F2-SEC-09, ADR-048, Q-410, Q-553, Q-554):
 * gli scenari sono in {@link PortalOidcScenarios} (righe TB-WAL-MBP-020…033), condivisi con
 * {@code TestbookWalMemberPrincipalIT}, che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class WalletPortalOidcIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 910000;
    }
}
