package it.iren.loyalty.ledger.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Repositories {
    interface MovementRepository extends JpaRepository<Movement, UUID> {
        List<Movement> findByActionKey(String actionKey);
        List<Movement> findTop100ByMemberIdOrderByCreatedAtDesc(String memberId);
        boolean existsByActionKeyAndWallet(String actionKey, String wallet);
        /** Movimenti in sospeso la cui finestra è scaduta (RF-66). */
        List<Movement> findByAvailableAtLessThanEqual(Instant now);
        /** Accrediti scaduti senza movimento di scadenza già emesso (RF-09, RF-87). */
        @Query("""
                select m from Movement m where m.amount > 0 and m.expiresAt is not null and m.expiresAt <= :now
                and not exists (select 1 from Movement e where e.actionKey = concat(m.actionKey, ':EXPIRY'))""")
        List<Movement> findExpiredNotYetReversed(@Param("now") Instant now);
        /** Unità erogate al membro da una campagna dal momento dato (limiti RF-82). */
        @Query("select coalesce(sum(m.amount),0) from Movement m where m.memberId = :memberId and m.ruleVersion like :campaignPrefix and m.amount > 0 and m.createdAt >= :since")
        long unitsSince(@Param("memberId") String memberId, @Param("campaignPrefix") String campaignPrefix, @Param("since") Instant since);
    }
    interface BalanceRepository extends JpaRepository<Balance, Balance.Key> {
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        Optional<Balance> findByMemberIdAndWallet(String memberId, String wallet);
        List<Balance> findByMemberId(String memberId);
    }
    interface OutboxRepository extends JpaRepository<Outbox, UUID> {
        List<Outbox> findTop500ByPublishedAtIsNullOrderByCreatedAt();
    }
}
