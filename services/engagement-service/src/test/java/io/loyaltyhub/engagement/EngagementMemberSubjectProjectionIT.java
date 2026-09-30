package io.loyaltyhub.engagement;

/**
 * Proiezione locale {@code subjectRef → membro} di engagement-service (F2-SEC-09, ADR-048, Q-550): gli scenari sono in
 * {@link MemberSubjectProjectionScenarios} (righe TB-ENG-MBP-001…015), condivisi con {@code TestbookEngMemberSubjectIT},
 * che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class EngagementMemberSubjectProjectionIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 900000;
    }
}
