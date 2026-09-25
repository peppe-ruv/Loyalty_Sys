package io.loyaltyhub.campaign.testbook;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supporto dei casi d'integrazione del testbook TB-CMP: chiamate HTTP con l'attore simulato ({@code X-LH-Actor},
 * docs/06 §3), campagne fresche (un codice nuovo per riga), fatti e azioni pubblicati su Kafka, attese con polling e
 * timeout (mai sleep fissi di verifica). Stesso bootstrap di {@code CampaignServiceIT}: EmbeddedKafka + Postgres Zonky.
 */
final class CmpItSupport implements AutoCloseable {

    static final String MARKETING = "MARKETING:luca";
    static final String ADMIN = "ADMIN:marta";
    static final String LEGAL = "LEGAL:elena";
    static final String START = "2026-01-01T00:00:00Z";

    record Resp(int status, JsonNode body) {
        String code() {
            return body.path("code").asString("");
        }
    }

    final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;
    private KafkaProducer<String, String> producer;

    CmpItSupport(int port) {
        this.client = RestClient.create("http://localhost:" + port);
    }

    static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- HTTP ----------

    /** {@code actor} null ⇒ nessuna intestazione {@code X-LH-Actor}. */
    Resp send(String method, String path, String actor, Object body) {
        var spec = client.method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            JsonNode node;
            try {
                node = text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            } catch (Exception e) {
                node = mapper.getNodeFactory().stringNode(text);
            }
            return new Resp(res.getStatusCode().value(), node);
        });
    }

    Resp expect(String method, String path, String actor, Object body, int status) {
        Resp r = send(method, path, actor, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.body()).isEqualTo(status);
        return r;
    }

    /** Corpo base di una campagna valida (docs/servizi/campaign-service.md §5) con i campi indicati sovrascritti. */
    Map<String, Object> body(String code, String trigger, Map<String, Object> overrides) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("name", "Testbook " + code);
        b.put("triggerActionTypes", List.of(trigger));
        b.put("audience", Map.of("all", true, "tiers", List.of(), "segments", List.of()));
        b.put("conditions", Map.of("op", "all", "rules", List.of()));
        b.put("effects", List.of(Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 10)));
        b.put("limits", Map.of());
        b.put("schedule", Map.of("startAt", START));
        b.put("priority", 100);
        b.put("visibleInPortal", false);
        b.put("labels", List.of());
        if (overrides != null) {
            b.putAll(overrides);
        }
        return b;
    }

    /** Crea (MARKETING) e ritorna la campagna creata. */
    JsonNode create(String code, String trigger, Map<String, Object> overrides) {
        return expect("POST", "/v1/campaigns", MARKETING, body(code, trigger, overrides), 201).body();
    }

    /** Porta una campagna DRAFT (senza approvazione richiesta) nello stato indicato, con transizioni ADMIN. */
    void toState(String id, String state) {
        List<String> path = switch (state) {
            case "DRAFT" -> List.of();
            case "IN_REVIEW" -> List.of("SUBMIT");
            case "APPROVED" -> List.of("SUBMIT", "APPROVE");
            case "LIVE" -> List.of("PUBLISH");
            case "PAUSED" -> List.of("PUBLISH", "PAUSE");
            case "ENDED" -> List.of("PUBLISH", "END");
            case "ARCHIVED" -> List.of("ARCHIVE");
            default -> throw new IllegalArgumentException(state);
        };
        for (String a : path) {
            transition(id, ADMIN, a, "preparazione del caso", 200);
        }
        assertThat(state(id)).as("stato di partenza").isEqualTo(state);
    }

    Resp transition(String id, String actor, String action, String comment, int expected) {
        Map<String, Object> req = new HashMap<>();
        req.put("action", action);
        if (comment != null) {
            req.put("comment", comment);
        }
        return expect("POST", "/v1/campaigns/" + id + "/transitions", actor, req, expected);
    }

    JsonNode get(String id) {
        return expect("GET", "/v1/campaigns/" + id, "ANALYST:sara", null, 200).body();
    }

    String state(String id) {
        return get(id).path("status").asString();
    }

    String idOf(String code) {
        for (JsonNode c : expect("GET", "/v1/campaigns", "ANALYST:sara", null, 200).body()) {
            if (code.equals(c.path("code").asString())) {
                return c.path("id").asString();
            }
        }
        throw new AssertionError("campagna non trovata: " + code);
    }

    // ---------- Kafka ----------

    private KafkaProducer<String, String> producer() {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                    "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        }
        return producer;
    }

    void publish(String topic, String key, Map<String, Object> event) {
        try {
            producer().send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fatto member.* verso lo snapshot locale di campaign (docs/servizi/campaign-service.md §4). */
    void fact(String type, String memberId, Map<String, Object> data) {
        String id = "tbfact-" + System.nanoTime();
        publish("lh.facts.v1", memberId, Map.of(
                "specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:member",
                "type", "io.loyaltyhub.fact." + type, "subject", "member:" + memberId,
                "time", "2026-09-01T08:00:00Z", "lhcorrelationid", id, "lhhop", 0, "data", data));
    }

    /** Azione su {@code lh.actions.v1} (docs/05 §3): tipo breve, istante di business esplicito. */
    void action(String id, String shortType, String memberId, String time, JsonNode data) {
        publish("lh.actions.v1", memberId, Map.of(
                "specversion", "1.0", "id", id, "source", "urn:loyaltyhub:source:ecommerce",
                "type", "io.loyaltyhub.action." + shortType, "subject", "member:" + memberId,
                "time", time, "lhcorrelationid", id, "lhhop", 0,
                "data", data == null ? mapper.createObjectNode() : data));
    }

    /** Registra un membro fresco (fatto member.registered) e attende che lo snapshot sia valutabile. */
    void registerMember(String memberId, String tier) {
        fact("member.registered", memberId, Map.of("status", "ACTIVE", "tier", tier,
                "registeredAt", "2026-01-15T10:00:00Z", "attributes", Map.of()));
        await("snapshot di " + memberId, () -> simulateOutcome(memberId), "NO_MATCH"::equals);
    }

    /** Esito della simulazione su un insieme vuoto di campagne: NO_MATCH se lo snapshot esiste ed è ACTIVE. */
    String simulateOutcome(String memberId) {
        ObjectNode req = mapper.createObjectNode();
        req.putObject("action").put("type", "tbcmp.probe").put("time", "2026-09-15T09:00:00Z");
        req.put("memberId", memberId);
        req.putArray("campaignIds").add("NESSUNA");
        return expect("POST", "/v1/campaigns/simulate", MARKETING, req, 200).body().path("outcome").asString();
    }

    /** Riga del registro valutazioni di un'azione reale: attende la valutazione del consumer (timeout 20 s). */
    JsonNode awaitEvaluation(String actionId) {
        Resp r = await("valutazione di " + actionId, () -> send("GET", "/v1/evaluations/" + actionId, "ANALYST:sara", null),
                x -> x.status() == 200);
        JsonNode body = r.body();
        return body.isString() ? mapper.readTree(body.asString()) : body;
    }

    /** Esito (outcome) di un'azione reale dal registro, per membro. */
    String awaitOutcome(String memberId, String actionId) {
        awaitEvaluation(actionId);
        for (JsonNode row : expect("GET", "/v1/evaluations?memberId=" + memberId + "&limit=200", "ANALYST:sara", null, 200).body()) {
            if (actionId.equals(row.path("actionId").asString())) {
                return row.path("outcome").asString();
            }
        }
        throw new AssertionError("azione non nel registro: " + actionId);
    }

    <T> T await(String what, Supplier<T> probe, Predicate<T> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        T value = probe.get();
        while (!done.test(value) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(150); // intervallo di polling, non un'attesa di verifica
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            value = probe.get();
        }
        assertThat(done.test(value)).as("attesa di " + what + " (ultimo valore: " + value + ")").isTrue();
        return value;
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.close();
        }
    }
}
