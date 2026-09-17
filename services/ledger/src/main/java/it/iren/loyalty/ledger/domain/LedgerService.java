package it.iren.loyalty.ledger.domain;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.common.event.EventTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accredita, addebita, blocca, trasferisce e storna movimenti in modo idempotente e atomico con il saldo e l'outbox
 * (RF-04, RF-15, RF-66, RF-87, RF-88). Scadenza e sospensione: quelle esplicite della campagna (RF-83) prevalgono su
 * quelle del wallet. Il saldo non scende sotto zero salvo wallet con {@code allowNegative}; uno storno che lo porterebbe
 * negativo crea un debito segnalato al customer care (RF-04).
 */
@Service
public class LedgerService {
    public record Posting(Currency currency, long amount, String reason, String ruleVersion, Instant expiresAt, int lockDays, Instant pendingUntil) {
        public Posting(Currency currency, long amount, String reason, String ruleVersion, Instant expiresAt) { this(currency, amount, reason, ruleVersion, expiresAt, 0, null); }
        public Posting(Currency currency, long amount, String reason, String ruleVersion, Instant expiresAt, int lockDays) { this(currency, amount, reason, ruleVersion, expiresAt, lockDays, null); }
    }
    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String m) { super(m); }
    }

    private final Repositories.MovementRepository movements;
    private final Repositories.BalanceRepository balances;
    private final Repositories.OutboxRepository outbox;
    private final WalletTypeSource wallets;

    public LedgerService(Repositories.MovementRepository movements, Repositories.BalanceRepository balances, Repositories.OutboxRepository outbox, WalletTypeSource wallets) {
        this.movements = movements;
        this.balances = balances;
        this.outbox = outbox;
        this.wallets = wallets;
    }

    /** Accredito da campagna: idempotente per (actionKey, wallet). */
    @Transactional
    public List<Movement> post(String memberId, String actionKey, List<Posting> postings) {
        Instant now = Instant.now();
        return postings.stream()
                .filter(p -> !movements.existsByActionKeyAndWallet(actionKey, p.currency().code()))
                .map(p -> {
                    WalletType w = walletOf(p.currency());
                    Instant expires = p.expiresAt() != null ? p.expiresAt() : p.amount() > 0 ? w.expiresAtFor(now) : null;
                    Instant pending = p.pendingUntil() != null ? p.pendingUntil() : p.lockDays() > 0 && p.amount() > 0 ? now.plus(java.time.Duration.ofDays(p.lockDays())) : p.amount() > 0 ? w.pendingUntilFor(now) : null;
                    return apply(Movement.of(memberId, p.currency(), p.amount(), p.reason(), actionKey, p.ruleVersion(), expires, pending), !w.allowNegative() && p.amount() < 0);
                })
                .toList();
    }

    /** Riscatto o "paga con i punti": scala unità solo se disponibili; verifica e scrittura nella stessa transazione (RF-15). */
    @Transactional
    public Movement debit(String memberId, String actionKey, long points, String reason) { return debit(memberId, Currency.PREMIO, actionKey, points, reason); }

    @Transactional
    public Movement debit(String memberId, Currency wallet, String actionKey, long points, String reason) {
        if (points <= 0) throw new IllegalArgumentException("points must be positive");
        if (!walletOf(wallet).spendable()) throw new IllegalArgumentException("wallet " + wallet + " is not spendable");
        return apply(Movement.of(memberId, wallet, -points, reason, actionKey, null, null).withKind("SPEND"), !walletOf(wallet).allowNegative());
    }

    /** Blocco/sblocco unità (RF-88): antifrode o contestazione; il bloccato non è spendibile né scade. */
    @Transactional
    public void block(String memberId, Currency wallet, long amount, String actionKey, String reason, boolean unblock) {
        Balance b = balanceOf(memberId, wallet);
        if (!unblock && b.getAvailable() < amount) throw new InsufficientBalanceException("cannot block " + amount + ", available " + b.getAvailable());
        if (unblock && b.getBlocked() < amount) throw new IllegalArgumentException("cannot unblock " + amount + ", blocked " + b.getBlocked());
        if (unblock) b.unblock(amount); else b.block(amount);
        Movement m = Movement.of(memberId, wallet, unblock ? amount : -amount, reason, actionKey, null, null).withKind(unblock ? "UNBLOCK" : "BLOCK");
        movements.save(m);
        emit(m, b);
    }

    /** Trasferimento tra membri (RF-88): due movimenti nella stessa transazione, idempotenti per chiave. */
    @Transactional
    public List<Movement> transfer(String fromMemberId, String toMemberId, Currency wallet, long amount, String transferKey, String comment) {
        if (amount <= 0 || fromMemberId.equals(toMemberId)) throw new IllegalArgumentException("invalid transfer");
        if (movements.existsByActionKeyAndWallet(transferKey, wallet.code())) return movements.findByActionKey(transferKey);
        Movement out = apply(Movement.of(fromMemberId, wallet, -amount, "TRANSFER:" + comment, transferKey, null, null).withKind("TRANSFER_OUT"), true);
        Movement in = apply(Movement.of(toMemberId, wallet, amount, "TRANSFER:" + comment, transferKey, null, walletOf(wallet).expiresAtFor(Instant.now())).withKind("TRANSFER_IN"), false);
        return List.of(out, in);
    }

    /** Rilascio dei punti in sospeso la cui finestra è scaduta (RF-66); invocato dallo scheduler. */
    @Transactional
    public int releasePending(Instant now) {
        int n = 0;
        for (Movement m : movements.findByAvailableAtLessThanEqual(now)) {
            Balance b = balanceOf(m.getMemberId(), m.getCurrency());
            b.release(m.getAmount());
            m.release();
            movements.save(m);
            n++;
        }
        return n;
    }

    /** Scadenza (RF-09, RF-87): per ogni accredito scaduto e non ancora consumato genera il movimento opposto con causale EXPIRY. */
    @Transactional
    public int expire(Instant now) {
        int n = 0;
        for (Movement m : movements.findExpiredNotYetReversed(now)) {
            Balance b = balanceOf(m.getMemberId(), m.getCurrency());
            long expirable = Math.min(m.getAmount(), Math.max(0, b.getAvailable()));
            if (expirable <= 0) continue;
            b.expire(expirable);
            Movement r = Movement.of(m.getMemberId(), m.getCurrency(), -expirable, "EXPIRY", m.getActionKey() + ":EXPIRY", m.getRuleVersion(), null).withKind("EXPIRY");
            movements.save(r);
            emit(r, b);
            n++;
        }
        return n;
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
        Balance b = balanceOf(m.getMemberId(), m.getCurrency());
        boolean pending = m.isPendingAt(Instant.now());
        long next = pending ? b.getAvailable() : b.getAvailable() + m.getAmount();
        if (strict && next < 0) {
            throw new InsufficientBalanceException("member " + m.getMemberId() + " has " + b.getAvailable() + " " + m.getCurrency());
        }
        if (pending) b.hold(m.getAmount()); else b.apply(m.getAmount());
        movements.save(m);
        emit(m, b);
        return m;
    }

    private Balance balanceOf(String memberId, Currency wallet) {
        return balances.findByMemberIdAndWallet(memberId, wallet.code()).orElseGet(() -> balances.save(new Balance(memberId, wallet)));
    }

    private WalletType walletOf(Currency c) {
        return wallets.byCode(c.code()).orElseThrow(() -> new IllegalArgumentException("unknown wallet " + c.code()));
    }

    private void emit(Movement m, Balance b) {
        var event = CanonicalEvents.of(EventTypes.MOVEMENT_V1, "urn:iren:loyalty:ledger", "member:" + m.getMemberId(), Map.of(
                "movementId", m.getId().toString(), "currency", m.getWallet(), "kind", m.getKind(), "amount", m.getAmount(),
                "reason", m.getReason(), "actionKey", m.getActionKey(), "balance", b.getAvailable(),
                "pending", b.getPending(), "blocked", b.getBlocked(), "debt", b.getAvailable() < 0));
        outbox.save(new Outbox(EventTypes.TOPIC_MOVEMENTS, m.getMemberId(), CanonicalEvents.serialize(event)));
    }
}
