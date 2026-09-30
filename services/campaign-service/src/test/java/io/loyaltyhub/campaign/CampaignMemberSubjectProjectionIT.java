package io.loyaltyhub.campaign;

/**
 * Proiezione locale {@code subjectRef → membro} di campaign-service (F2-SEC-09, ADR-048, Q-550): gli scenari sono in
 * {@link MemberSubjectProjectionScenarios} (righe TB-CMP-MBP-001…015), condivisi con {@code TestbookCmpMemberSubjectIT},
 * che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class CampaignMemberSubjectProjectionIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 940000;
    }
}
