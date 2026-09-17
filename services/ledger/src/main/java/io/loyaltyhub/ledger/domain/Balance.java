package io.loyaltyhub.ledger.domain;

import io.loyaltyhub.common.event.Currency;
import jakarta.persistence.*;

/** Saldo materializzato per membro e wallet (RF-87): disponibile, in sospeso, bloccato, maturato e speso cumulati; aggiornato nella stessa transazione del movimento. */
@Entity
@Table(name = "balance", schema = "ledger")
@IdClass(Balance.Key.class)
public class Balance {
    public record Key(String memberId, String wallet) implements java.io.Serializable {}

    @Id @Column(name = "member_id") private String memberId;
    @Id @Column(name = "currency") private String wallet;
    @Column(nullable = false) private long available;
    @Column(nullable = false) private long pending;
    @Column(nullable = false) private long blocked;
    @Column(name = "earned_total", nullable = false) private long earnedTotal;
    @Column(name = "spent_total", nullable = false) private long spentTotal;
    @Column(name = "expired_total", nullable = false) private long expiredTotal;
    @Version private long version;

    protected Balance() {}
    public Balance(String memberId, Currency currency) { this.memberId = memberId; this.wallet = currency.code(); }

    public void apply(long delta) {
        this.available += delta;
        if (delta > 0) earnedTotal += delta; else spentTotal += -delta;
    }
    public void expire(long amount) { this.available -= amount; this.expiredTotal += amount; }
    public void hold(long delta) { this.pending += delta; }
    /** Sposta punti dal sospeso al disponibile (RF-66). */
    public void release(long amount) { this.pending -= amount; this.available += amount; this.earnedTotal += amount; }
    /** Blocco/sblocco (RF-88): unità congelate per verifica antifrode o contestazione, non spendibili. */
    public void block(long amount) { this.available -= amount; this.blocked += amount; }
    public void unblock(long amount) { this.blocked -= amount; this.available += amount; }

    public long getAvailable() { return available; }
    public long getPending() { return pending; }
    public long getBlocked() { return blocked; }
    public long getEarnedTotal() { return earnedTotal; }
    public long getSpentTotal() { return spentTotal; }
    public long getExpiredTotal() { return expiredTotal; }
    public String getMemberId() { return memberId; }
    public String getWallet() { return wallet; }
    public Currency getCurrency() { return Currency.of(wallet); }
}
