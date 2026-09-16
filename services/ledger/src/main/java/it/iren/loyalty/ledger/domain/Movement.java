package it.iren.loyalty.ledger.domain;

import it.iren.loyalty.common.event.Currency;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Movimento del ledger: immutabile, con segno, riconducibile all'azione e alla versione di regola (D10, RF-06).
 * Gli storni sono movimenti opposti che citano l'originale (RF-04).
 */
@Entity
@Table(name = "movement", schema = "ledger")
public class Movement {
    @Id
    private UUID id;
    @Column(name = "member_id", nullable = false)
    private String memberId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;
    @Column(nullable = false)
    private long amount;
    @Column(nullable = false)
    private String reason;
    @Column(name = "action_key", nullable = false)
    private String actionKey;
    @Column(name = "rule_version")
    private String ruleVersion;
    @Column(name = "reversal_of")
    private UUID reversalOf;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Movement() {}

    public static Movement of(String memberId, Currency currency, long amount, String reason, String actionKey, String ruleVersion, Instant expiresAt) {
        Movement m = new Movement();
        m.id = UUID.randomUUID();
        m.memberId = memberId;
        m.currency = currency;
        m.amount = amount;
        m.reason = reason;
        m.actionKey = actionKey;
        m.ruleVersion = ruleVersion;
        m.expiresAt = expiresAt;
        m.createdAt = Instant.now();
        return m;
    }

    public Movement reverse(String reason) {
        Movement r = of(memberId, currency, -amount, reason, actionKey + ":REVERSAL", ruleVersion, null);
        r.reversalOf = this.id;
        return r;
    }

    public UUID getId() { return id; }
    public String getMemberId() { return memberId; }
    public Currency getCurrency() { return currency; }
    public long getAmount() { return amount; }
    public String getReason() { return reason; }
    public String getActionKey() { return actionKey; }
    public String getRuleVersion() { return ruleVersion; }
    public UUID getReversalOf() { return reversalOf; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
