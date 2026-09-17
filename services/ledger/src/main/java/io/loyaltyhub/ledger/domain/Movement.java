package io.loyaltyhub.ledger.domain;

import io.loyaltyhub.common.event.Currency;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Movimento del ledger: immutabile, con segno, riconducibile all'azione e alla versione di regola (ADR-010, RF-06).
 * Gli storni sono movimenti opposti che citano l'originale (RF-04). Il wallet è un codice (RF-87): PREMIO, STATUS o
 * wallet configurati. Kind: EARN, SPEND, EXPIRY, REVERSAL, BLOCK, UNBLOCK, TRANSFER_OUT, TRANSFER_IN, MANUAL (RF-88).
 */
@Entity
@Table(name = "movement", schema = "ledger")
public class Movement {
    @Id
    private UUID id;
    @Column(name = "member_id", nullable = false)
    private String memberId;
    @Column(name = "currency", nullable = false)
    private String wallet;
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
    /** Punti in sospeso fino a questo istante (RF-66, finestra di reso/ripensamento); null = subito disponibili. */
    @Column(name = "available_at")
    private Instant availableAt;
    @Column(name = "kind", nullable = false)
    private String kind = "EARN";
    @Column(name = "labels")
    private String labels;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Movement() {}

    public static Movement of(String memberId, Currency currency, long amount, String reason, String actionKey, String ruleVersion, Instant expiresAt) {
        return of(memberId, currency, amount, reason, actionKey, ruleVersion, expiresAt, null);
    }

    public static Movement of(String memberId, Currency currency, long amount, String reason, String actionKey, String ruleVersion, Instant expiresAt, Instant availableAt) {
        Movement m = new Movement();
        m.availableAt = availableAt;
        m.id = UUID.randomUUID();
        m.memberId = memberId;
        m.wallet = currency.code();
        m.amount = amount;
        m.reason = reason;
        m.actionKey = actionKey;
        m.ruleVersion = ruleVersion;
        m.expiresAt = expiresAt;
        m.createdAt = Instant.now();
        m.kind = amount < 0 ? ("EXPIRY".equals(reason) ? "EXPIRY" : "SPEND") : "EARN";
        return m;
    }

    public Movement withKind(String kind) { this.kind = kind; return this; }
    public Movement withLabels(String labels) { this.labels = labels; return this; }

    public Movement reverse(String reason) {
        Movement r = of(memberId, getCurrency(), -amount, reason, actionKey + ":REVERSAL", ruleVersion, null);
        r.reversalOf = this.id;
        r.kind = "REVERSAL";
        return r;
    }

    public UUID getId() { return id; }
    public String getMemberId() { return memberId; }
    public Currency getCurrency() { return Currency.of(wallet); }
    public String getWallet() { return wallet; }
    public long getAmount() { return amount; }
    public String getReason() { return reason; }
    public String getActionKey() { return actionKey; }
    public String getRuleVersion() { return ruleVersion; }
    public UUID getReversalOf() { return reversalOf; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAvailableAt() { return availableAt; }
    public String getKind() { return kind; }
    public String getLabels() { return labels; }
    public boolean isPendingAt(Instant now) { return availableAt != null && now.isBefore(availableAt); }
    /** Rilascio dei punti in sospeso: da quel momento il movimento conta nel disponibile. */
    public void release() { this.availableAt = null; }
    public Instant getCreatedAt() { return createdAt; }
}
