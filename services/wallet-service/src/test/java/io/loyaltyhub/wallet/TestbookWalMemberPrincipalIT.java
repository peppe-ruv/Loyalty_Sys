package io.loyaltyhub.wallet;

/**
 * Righe TB-WAL-MBP-020…033 del testbook (docs/testbook/TB-WAL-wallet.md §22): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link PortalOidcScenarios}, gli stessi di
 * {@link WalletPortalOidcIT}: stesso contesto, id di membri e soggetti distinti.
 */
class TestbookWalMemberPrincipalIT extends PortalOidcScenarios {

    @Override
    int idBase() {
        return 930000;
    }
}
