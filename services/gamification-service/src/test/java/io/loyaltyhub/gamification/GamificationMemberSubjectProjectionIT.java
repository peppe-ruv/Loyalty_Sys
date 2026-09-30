package io.loyaltyhub.gamification;

/**
 * Proiezione locale {@code subjectRef → membro} di gamification-service (F2-SEC-09, ADR-048, Q-550): gli scenari sono in
 * {@link MemberSubjectProjectionScenarios} (righe TB-GAM-MBP-001…016), condivisi con {@code TestbookGamMemberSubjectIT},
 * che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class GamificationMemberSubjectProjectionIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 900000;
    }
}
