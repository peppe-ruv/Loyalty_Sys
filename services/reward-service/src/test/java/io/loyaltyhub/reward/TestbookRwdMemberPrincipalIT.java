package io.loyaltyhub.reward;

/**
 * Righe TB-RWD-MBP-020…036 del testbook (docs/testbook/TB-RWD-premi.md §21): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link PortalOidcScenarios}, gli stessi di
 * {@link RewardPortalOidcIT}: stesso contesto, id di membri e soggetti distinti.
 */
class TestbookRwdMemberPrincipalIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 930000;
    }
}
