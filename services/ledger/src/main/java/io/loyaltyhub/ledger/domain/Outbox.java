package io.loyaltyhub.ledger.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Outbox transazionale (ADR-010): l'evento è scritto con il movimento e pubblicato da un relay (o da Debezium via CDC). */
@Entity
@Table(name = "outbox", schema = "ledger")
public class Outbox {
    @Id private UUID id = UUID.randomUUID();
    @Column(nullable = false) private String topic;
    @Column(name = "message_key", nullable = false) private String key;
    @Column(nullable = false) private byte[] payload;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "published_at") private Instant publishedAt;

    protected Outbox() {}
    public Outbox(String topic, String key, byte[] payload) { this.topic = topic; this.key = key; this.payload = payload; }
    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getKey() { return key; }
    public byte[] getPayload() { return payload; }
    public void markPublished() { this.publishedAt = Instant.now(); }
}
