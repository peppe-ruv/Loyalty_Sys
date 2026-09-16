package it.iren.loyalty.ledger.domain;

import it.iren.loyalty.common.event.Currency;
import jakarta.persistence.*;

/** Saldo materializzato per membro e valuta; aggiornato nella stessa transazione del movimento. */
@Entity
@Table(name = "balance", schema = "ledger")
@IdClass(Balance.Key.class)
public class Balance {
    public record Key(String memberId, Currency currency) implements java.io.Serializable {}

    @Id @Column(name = "member_id") private String memberId;
    @Id @Enumerated(EnumType.STRING) private Currency currency;
    @Column(nullable = false) private long available;
    @Column(nullable = false) private long pending;
    @Version private long version;

    protected Balance() {}
    public Balance(String memberId, Currency currency) { this.memberId = memberId; this.currency = currency; }

    public void apply(long delta) { this.available += delta; }
    public long getAvailable() { return available; }
    public long getPending() { return pending; }
    public String getMemberId() { return memberId; }
    public Currency getCurrency() { return currency; }
}
