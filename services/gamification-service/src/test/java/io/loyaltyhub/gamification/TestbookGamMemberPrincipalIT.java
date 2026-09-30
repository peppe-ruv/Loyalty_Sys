package io.loyaltyhub.gamification;

/**
 * Righe TB-GAM-MBP-020…037 del testbook (docs/testbook/TB-GAM-gioco.md §22): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link PortalOidcScenarios}, gli stessi di
 * {@link GamificationPortalOidcIT}: stesso contesto, id di membri e soggetti distinti.
 */
class TestbookGamMemberPrincipalIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 930000;
    }
}
