package io.loyaltyhub.engagement;

/**
 * Righe TB-ENG-MBP-020…037 del testbook (docs/testbook/TB-ENG-engagement.md §18): le esegue {@code scripts/testbook.sh}
 * per nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link PortalOidcScenarios}, gli stessi di
 * {@link EngagementPortalOidcIT}: stesso contesto, id di membri e soggetti distinti.
 */
class TestbookEngMemberPrincipalIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 930000;
    }
}
