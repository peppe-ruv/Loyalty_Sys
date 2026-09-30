package io.loyaltyhub.gamification;

/**
 * Righe TB-GAM-MBP-001…016 del testbook (docs/testbook/TB-GAM-gioco.md §22): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link MemberSubjectProjectionScenarios}, gli stessi di
 * {@link GamificationMemberSubjectProjectionIT}: stesso contesto, id di membri distinti.
 */
class TestbookGamMemberSubjectIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 920000;
    }
}
