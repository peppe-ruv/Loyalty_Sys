package io.loyaltyhub.campaign;

/**
 * Righe TB-CMP-MBP-001…015 del testbook (docs/testbook/TB-CMP-campagne.md §16): le esegue {@code scripts/testbook.sh} per
 * nome di classe ({@code Testbook*IT}). Gli scenari sono in {@link MemberSubjectProjectionScenarios}, gli stessi di
 * {@link CampaignMemberSubjectProjectionIT}: stesso contesto, id di membri distinti.
 */
class TestbookCmpMemberSubjectIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 950000;
    }
}
