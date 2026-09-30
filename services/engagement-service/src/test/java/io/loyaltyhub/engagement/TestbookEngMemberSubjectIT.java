package io.loyaltyhub.engagement;

/**
 * Righe TB-ENG-MBP-001…015 del testbook (docs/testbook/TB-ENG-engagement.md §18): le esegue {@code scripts/testbook.sh}
 * per nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link MemberSubjectProjectionScenarios}, gli stessi di
 * {@link EngagementMemberSubjectProjectionIT}: stesso contesto, id di membri distinti.
 */
class TestbookEngMemberSubjectIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 920000;
    }
}
