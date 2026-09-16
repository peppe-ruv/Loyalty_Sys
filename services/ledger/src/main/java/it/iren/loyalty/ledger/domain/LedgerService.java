package it.iren.loyalty.ledger.domain;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.common.event.EventTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Accredita e storna movimenti in modo idempotente e atomico con il saldo e l'outbox.
 * Il saldo PREMIO non scende sotto zero: uno storno che lo porterebbe negativo crea un debito segnalato al customer care (RF-04).
 */
@Service
public class LedgerService {
    public record Posting(Currency currency, long amount, String reason, String ruleVersion, Instant expiresAt) {}
    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String m) { super(m); }
    }

    private final Repositories.MovementRepository movements;
    private final Repositories.BalanceRepository balances;
    private final Repositories.OutboxRepository outbox;

    public LedgerService(Repositories.MovementRepository movements, Repositories.BalanceRepository balances, Repositories.OutboxRepository outbox) {
        this.movements = movements;
        this.balances = balances;
        this.outbox = outbox;
    }

    /** Accredito da regola: idempotente per (actionKey, currency). */
    @Transactional
    public List<Movement> post(String memberId, String actionKey, List<Posting> postings) {
        return postings.stream()
                .filter(p -> !movements.existsByActionKeyAndCurrency(actionKey, p.currency()))
                .map(p -> apply(Movement.of(memberId, p.currency(), p.amount(), p.reason(), actionKey, p.ruleVersion(), p.expiresAt()), false))
                .toList();
    }

    /** Riscatto: scala punti PREMIO solo se disponibili; la verifica e la scrittura sono nella stessa transazione (RF-15). */
    @Transactional
    public Movement debit(String memberId, String actionKey, long points, String reason) {
        if (points <= 0) throw new IllegalArgumentException("points must be positive");
        return apply(Movement.of(memberId, Currency.PREMIO, -points, reason, actionKey, null, null), true);
    }

    /** Storno di tutti i movimenti di un'azione (RI-08). */
    @Transactional
    public List<Movement> reverse(String originalActionKey, String reason) {
        return movements.findByActionKey(originalActionKey).stream()
                .filter(m -> m.getReversalOf() == null)
                .map(m -> apply(m.reverse(reason), false))
                .toList();
    }

    private Movement apply(Movement m, boolean strict) {
        Balance b = balances.findByMemberIdAndCurrency(m.getMemberId(), m.getCurrency())
                .orElseGet(() -> balances.save(new Balance(m.getMemberId(), m.getCurrency())));
        long next = b.getAvailable() + m.getAmount();
        if (strict && next < 0) {
            throw new InsufficientBalanceException("member " + m.getMemberId() + " has " + b.getAvailable() + " " + m.getCurrency());
        }
        b.apply(m.getAmount());
        movements.save(m);
        var event = CanonicalEvents.of(EventTypes.MOVEMENT_V1, "urn:iren:loyalty:ledger", "member:" + m.getMemberId(), Map.of(
                "movementId", m.getId().toString(), "currency", m.getCurrency().name(), "amount", m.getAmount(),
                "reason", m.getReason(), "actionKey", m.getActionKey(), "balance", next,
                "debt", next < 0));
        outbox.save(new Outbox(EventTypes.TOPIC_MOVEMENTS, m.getMemberId(), CanonicalEvents.serialize(event)));
        return m;
    }
}
