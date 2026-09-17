package it.iren.loyalty.ledger.domain;

import it.iren.loyalty.common.event.Currency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Repositories {
    interface MovementRepository extends JpaRepository<Movement, UUID> {
        List<Movement> findByActionKey(String actionKey);
        List<Movement> findTop100ByMemberIdOrderByCreatedAtDesc(String memberId);
        boolean existsByActionKeyAndCurrency(String actionKey, Currency currency);
        /** Movimenti in sospeso la cui finestra è scaduta (RF-66). */
        List<Movement> findByAvailableAtLessThanEqual(java.time.Instant now);
        /** Accrediti PREMIO scaduti senza movimento di scadenza già emesso (RF-09). */
        @org.springframework.data.jpa.repository.Query("""
                select m from Movement m where m.currency = it.iren.loyalty.common.event.Currency.PREMIO and m.amount > 0
                and m.expiresAt is not null and m.expiresAt <= :now
                and not exists (select 1 from Movement e where e.actionKey = concat(m.actionKey, ':EXPIRY'))""")
        List<Movement> findExpiredNotYetReversed(@org.springframework.data.repository.query.Param("now") java.time.Instant now);
    }
    interface BalanceRepository extends JpaRepository<Balance, Balance.Key> {
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        Optional<Balance> findByMemberIdAndCurrency(String memberId, Currency currency);
        List<Balance> findByMemberId(String memberId);
    }
    interface OutboxRepository extends JpaRepository<Outbox, UUID> {
        List<Outbox> findTop500ByPublishedAtIsNullOrderByCreatedAt();
    }
}
