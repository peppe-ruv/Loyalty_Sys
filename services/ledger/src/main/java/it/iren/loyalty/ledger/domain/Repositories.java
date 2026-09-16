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
