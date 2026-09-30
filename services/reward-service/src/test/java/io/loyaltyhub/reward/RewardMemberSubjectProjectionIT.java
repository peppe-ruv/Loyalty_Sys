package io.loyaltyhub.reward;

/**
 * Proiezione locale {@code subjectRef → membro} di reward-service (F2-SEC-09, ADR-048, Q-550): gli scenari sono in
 * {@link MemberSubjectProjectionScenarios} (righe TB-RWD-MBP-001…016), condivisi con {@code TestbookRwdMemberSubjectIT},
 * che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class RewardMemberSubjectProjectionIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 900000;
    }
}
