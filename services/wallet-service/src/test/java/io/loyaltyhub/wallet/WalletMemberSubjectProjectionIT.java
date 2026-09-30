package io.loyaltyhub.wallet;

/**
 * Proiezione locale {@code subjectRef → membro} di wallet-service (F2-SEC-09, ADR-048, Q-550): gli scenari sono in
 * {@link MemberSubjectProjectionScenarios} (righe TB-WAL-MBP-001…014), condivisi con {@code TestbookWalMemberSubjectIT},
 * che il rapporto del testbook esegue per nome di classe (docs/16).
 */
class WalletMemberSubjectProjectionIT extends MemberSubjectProjectionScenarios {

    @Override
    int idBase() {
        return 900000;
    }
}
