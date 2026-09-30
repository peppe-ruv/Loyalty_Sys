package io.loyaltyhub.reward;

/**
 * Righe TB-RWD-MBP-001…016 del testbook (docs/testbook/TB-RWD-premi.md §21): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link MemberSubjectProjectionScenarios}, gli stessi di
 * {@link RewardMemberSubjectProjectionIT}: stesso contesto, id di membri distinti.
 */
class TestbookRwdMemberSubjectIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 920000;
    }
}
