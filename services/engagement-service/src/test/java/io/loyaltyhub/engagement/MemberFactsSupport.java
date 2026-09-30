package io.loyaltyhub.engagement;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fatti veri di member-service e di wallet, pubblicati sul bus embedded dagli IT del membro dal token
 * (F2-SEC-09, ADR-048, Q-550): {@code member.registered}, {@code member.updated}, {@code member.status.changed} con o senza
 * {@code subjectRef} (assente, {@code null} o valore), in versione {@code :1} o {@code :2}. Solo dati fittizi.
 */
final class MemberFactsSupport {

    static final String FACTS = "lh.facts.v1";
    private static final String PREFIX = "io.loyaltyhub.fact.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MemberFactsSupport() {
    }

    /** Marcatore per «campo {@code subjectRef} assente» (diverso da {@code null}, che slega). */
    static final Object ABSENT = new Object();

    static RecordMetadata registered(String memberId, Object subjectRef, String time, int schemaVersion) {
        return registeredAs("EV-" + UUID.randomUUID(), memberId, subjectRef, time, schemaVersion);
    }

    /** Come {@link #registered} con l'id evento scelto: per rigiocare lo stesso evento (consegna doppia). */
    static RecordMetadata registeredAs(String eventId, String memberId, Object subjectRef, String time, int schemaVersion) {
        return member(eventId, "member.registered", memberId, subjectRef, time, schemaVersion, "ACTIVE");
    }

    static RecordMetadata updated(String memberId, Object subjectRef, String time, int schemaVersion) {
        return member("EV-" + UUID.randomUUID(), "member.updated", memberId, subjectRef, time, schemaVersion, "ACTIVE");
    }

    /** {@code member.updated} che porta il membro in {@code ANONYMIZED} (senza {@code subjectRef}). */
    static RecordMetadata updatedAnonymized(String memberId, String time) {
        return member("EV-" + UUID.randomUUID(), "member.updated", memberId, ABSENT, time, 2, "ANONYMIZED");
    }

    static RecordMetadata statusChanged(String memberId, String newStatus, String time) {
        Map<String, Object> data = new HashMap<>();
        data.put("memberId", memberId);
        data.put("previousStatus", "ACTIVE");
        data.put("newStatus", newStatus);
        return send(FACTS, memberId, envelope("EV-" + UUID.randomUUID(), PREFIX + "member.status.changed", memberId, time, data, null));
    }

    private static RecordMetadata member(String eventId, String type, String memberId, Object subjectRef, String time, int schemaVersion,
                                         String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("memberId", memberId);
        data.put("status", status);
        data.put("channel", "PORTAL");
        data.put("registeredAt", Instant.now().minus(1, ChronoUnit.DAYS).toString()); // il pop-up di benvenuto vale per gli iscritti da meno di 7 giorni
        if (subjectRef != ABSENT) {
            data.put("subjectRef", subjectRef); // null esplicito ⇒ slega
        }
        return send(FACTS, memberId, envelope(eventId, PREFIX + type, memberId, time, data,
                "urn:loyaltyhub:schema:fact." + type + ":" + schemaVersion));
    }

    /** Fatto {@code wallet.points.earned} di {@code amount} PTS: la regola NR-POINTS-EARNED lo rende in un messaggio dell'inbox. */
    static RecordMetadata pointsEarned(String memberId, String eventId, long amount) {
        Map<String, Object> data = Map.of("ledgerEntryId", "LED-" + eventId, "effectId", "EFF-" + eventId,
                "campaignCode", "CMP-PURCHASE-BASE", "currency", "PTS", "baseAmount", amount, "amount", amount,
                "balanceAfter", 2000 + amount, "pending", false);
        Map<String, Object> event = envelope(eventId, PREFIX + "wallet.points.earned", memberId, Instant.now().toString(), data, null);
        event.put("source", "urn:loyaltyhub:service:wallet");
        event.put("lhcausationid", "EFF-" + eventId);
        return send(FACTS, memberId, event);
    }

    private static Map<String, Object> envelope(String eventId, String type, String memberId, String time,
                                                Map<String, Object> data, String dataschema) {
        Map<String, Object> event = new HashMap<>();
        event.put("specversion", "1.0");
        event.put("id", eventId);
        event.put("source", "urn:loyaltyhub:service:member");
        event.put("type", type);
        event.put("subject", "member:" + memberId);
        event.put("time", time);
        event.put("lhcorrelationid", "COR-" + UUID.randomUUID());
        event.put("lhhop", 0);
        if (dataschema != null) {
            event.put("dataschema", dataschema);
        }
        event.put("data", data);
        return event;
    }

    private static RecordMetadata send(String topic, String key, Map<String, Object> event) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            return producer.send(new ProducerRecord<>(topic, key, MAPPER.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
