package io.loyaltyhub.wallet;

/**
 * Righe TB-WAL-MBP-001…014 del testbook (docs/testbook/TB-WAL-wallet.md §22): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link MemberSubjectProjectionScenarios}, gli stessi di
 * {@link WalletMemberSubjectProjectionIT}: stesso contesto, id di membri distinti.
 */
class TestbookWalMemberSubjectIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 920000;
    }
}
