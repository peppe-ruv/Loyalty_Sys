package io.loyaltyhub.campaign;

/**
 * Righe TB-CMP-MBP-020…034 del testbook (docs/testbook/TB-CMP-campagne.md §16): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link PortalOidcScenarios}, gli stessi di
 * {@link CampaignPortalOidcIT}: stesso contesto, id di membri e soggetti distinti.
 */
class TestbookCmpMemberPrincipalIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 970000;
    }
}
