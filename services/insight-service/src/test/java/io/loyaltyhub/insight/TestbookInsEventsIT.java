package io.loyaltyhub.insight;

import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — event store e pipeline (docs/testbook/TB-INS-insight.md §8–§10): ricerca eventi ({@code EVT}),
 * statistiche per topic ({@code PIP}), ingest dei 4 topic di dominio ({@code ING}), letture per ruolo ({@code ROL}).
 * Oracolo: insight §2–§5, docs/05 §1–§2, docs/06 §2–§3, docs/08 §2, BO-24, F-INS-01, F-INS-06, Q-111.
 */
class TestbookInsEventsIT extends TestbookInsBase {

    @Autowired
    private EventStoreRepository events;

    // ---------- EVT: ricerca ----------

    private void stored(String eventId, String topic, String family, String type, String source, String member,
                        String cor, Instant received, Map<String, ?> data) {
        String shortType = type.substring(type.indexOf('.', "io.loyaltyhub.".length()) + 1);
        String payload = mapper.writeValueAsString(envelope(eventId, type, source, "member:" + member, cor, null,
                received, data));
        assertThat(events.insert(new StoredEvent(eventId, topic, family, type, shortType, source, member, cor, null, 0,
                "system", null, received, null, 0, 0L, payload))).isTrue();
        setReceivedAt(eventId, received);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/eventi-filtri.csv", numLinesToSkip = 1)
    void ricerca(String id, String desc, String query, int expHttp, String expIdx) {
        String member = uid("MBR-EVT");
        String cor = uid("COR-EVT");
        String order = "ORD-88213-" + uid("X");
        Instant t0 = Instant.parse("2036-02-01T10:00:00Z");
        List<String> ids = List.of(uid("EVT-A"), uid("EVT-E"), uid("EVT-F"), uid("EVT-T"));
        stored(ids.get(0), "lh.actions.v1", "ACTION", ACTION + "purchase.completed", "urn:loyaltyhub:source:ecommerce",
                member, cor, t0, Map.of("orderId", order, "amount", 130));
        stored(ids.get(1), "lh.effects.v1", "EFFECT", EFFECT + "points.grant", "urn:loyaltyhub:service:campaign",
                member, cor, t0.plusSeconds(1), Map.of("amount", 162));
        stored(ids.get(2), "lh.facts.v1", "FACT", FACT + "wallet.points.earned", "urn:loyaltyhub:service:wallet",
                member, cor, t0.plusSeconds(2), Map.of("amount", 162, "currency", "PTS"));
        stored(ids.get(3), "lh.facts.v1", "FACT", FACT + "tier.upgraded", "urn:loyaltyhub:service:wallet",
                member, uid("COR-ALTRO"), t0.plusSeconds(3), Map.of("newTier", "GOLD"));
        String q = (query == null ? "" : query).replace("{cor}", cor).replace("{t1}", t0.plusSeconds(1).toString())
                .replace("{ordlow}", order.toLowerCase());
        Resp r = call("GET", "/v1/events?memberId=" + member + q, null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp != 200) {
            assertThat(r.code()).isEqualTo("BAD_REQUEST");
            return;
        }
        List<String> expected = new ArrayList<>();
        if (expIdx != null && !expIdx.isBlank()) {
            for (String s : expIdx.trim().split(" ")) {
                expected.add(ids.get(Integer.parseInt(s)));
            }
        }
        List<String> actual = new ArrayList<>();
        r.body().path("items").forEach(i -> actual.add(i.path("eventId").asString()));
        assertThat(actual).as("eventi e ordine (dal più recente)").containsExactlyElementsOf(expected);
        if (q.contains("size=")) {
            assertThat(r.body().path("page").path("totalItems").asLong()).as("page di docs/06 §2").isEqualTo(4);
        }
    }

    @Test
    @DisplayName("[TB-INS-EVT-016] dettaglio GET /v1/events/{id}: payload completo, tipo completo, causazione, partizione e offset")
    void detail() {
        String ev = uid("EVT-D");
        String cor = uid("COR-D");
        ObjectNode env = envelope(ev, FACT + "wallet.points.earned", "urn:loyaltyhub:service:wallet", "member:MBR-000003",
                cor, "EVT-CAUSA", Instant.parse("2026-09-18T10:15:00Z"), Map.of("amount", 162, "currency", "PTS"));
        long offset = publish("lh.facts.v1", env);
        awaitStored(ev);
        JsonNode d = get("/v1/events/" + ev);
        assertThat(d.path("type").asString()).isEqualTo(FACT + "wallet.points.earned");
        assertThat(d.path("causationId").asString()).isEqualTo("EVT-CAUSA");
        assertThat(d.path("payload").path("data").path("amount").asInt()).isEqualTo(162);
        assertThat(d.path("payload").path("lhtenant").asString()).isEqualTo("aurora");
        assertThat(d.path("partition").asInt()).isZero();
        assertThat(d.path("offset").asLong()).isEqualTo(offset);
    }

    @Test
    @DisplayName("[TB-INS-EVT-017] evento inesistente: 404 NOT_FOUND")
    void detailNotFound() {
        Resp r = call("GET", "/v1/events/EVT-NON-ESISTE", null, null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
    }

    // ---------- PIP: statistiche per topic ----------

    private JsonNode topicStat(String topic) {
        for (JsonNode t : get("/v1/pipeline/status").path("topics")) {
            if (topic.equals(t.path("topic").asString())) {
                return t;
            }
        }
        return mapper.createObjectNode();
    }

    private String effect(String ev, Instant time) {
        publish("lh.effects.v1", envelope(ev, EFFECT + "points.grant", "urn:loyaltyhub:service:campaign",
                "member:MBR-000002", uid("COR-PIP"), null, time, Map.of("amount", 1)));
        return ev;
    }

    @Test
    @DisplayName("[TB-INS-PIP-001] nuovo evento: countTotal del topic +1")
    void countIncrements() {
        long before = topicStat("lh.effects.v1").path("countTotal").asLong();
        awaitStored(effect(uid("EVT-PIP"), Instant.parse("2026-09-15T10:00:00Z")));
        assertThat(topicStat("lh.effects.v1").path("countTotal").asLong() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-PIP-002] evento duplicato: la statistica non conta la seconda consegna")
    void duplicateNotCounted() {
        long before = topicStat("lh.effects.v1").path("countTotal").asLong();
        String ev = uid("EVT-PIP");
        effect(ev, Instant.parse("2026-09-15T10:00:00Z"));
        effect(ev, Instant.parse("2026-09-15T10:00:00Z"));
        awaitStored(effect(uid("EVT-PIP"), Instant.parse("2026-09-15T10:00:01Z")));
        assertThat(topicStat("lh.effects.v1").path("countTotal").asLong() - before).as("evento + sentinella").isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-INS-PIP-003] evento con time più vecchio dell'ultimo: lastEventAt non torna indietro")
    void lastEventNotBack() {
        awaitStored(effect(uid("EVT-PIP"), Instant.now()));
        String last = topicStat("lh.effects.v1").path("lastEventAt").asString();
        awaitStored(effect(uid("EVT-PIP"), Instant.parse(last).minusSeconds(86_400)));
        assertThat(topicStat("lh.effects.v1").path("lastEventAt").asString()).isEqualTo(last);
    }

    @Test
    @DisplayName("[TB-INS-PIP-004] evento più recente dell'ultimo: lastEventAt avanza al suo time")
    void lastEventForward() {
        awaitStored(effect(uid("EVT-PIP"), Instant.now()));
        Instant next = Instant.parse(topicStat("lh.effects.v1").path("lastEventAt").asString()).plusSeconds(1);
        awaitStored(effect(uid("EVT-PIP"), next));
        assertThat(topicStat("lh.effects.v1").path("lastEventAt").asString()).isEqualTo(next.toString());
    }

    @Test
    @DisplayName("[TB-INS-PIP-005] offset per partizione: la partizione 0 riporta l'offset dell'ultimo record")
    void offsets() {
        String ev = uid("EVT-PIP");
        long offset = publish("lh.effects.v1", envelope(ev, EFFECT + "points.grant", "urn:loyaltyhub:service:campaign",
                "member:MBR-000002", uid("COR"), null, Instant.now(), Map.of()));
        awaitStored(ev);
        JsonNode offsets = mapper.readTree(topicStat("lh.effects.v1").path("lastOffsetByPartition").asString());
        assertThat(offsets.path("0").asLong()).isEqualTo(offset);
    }

    @Test
    @DisplayName("[TB-INS-PIP-006] volumi 1 h e 24 h per topic (insight §2 topic_stat count_1h/count_24h, BO-24)")
    void volumes() {
        awaitStored(effect(uid("EVT-PIP"), Instant.now()));
        JsonNode t = topicStat("lh.effects.v1");
        assertThat(t.path("count1h").asLong(-1)).as("count1h").isGreaterThanOrEqualTo(1);
        assertThat(t.path("count24h").asLong(-1)).as("count24h").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-PIP-007] ritardo stimato per topic (insight §3, BO-24)")
    void lag() {
        awaitStored(effect(uid("EVT-PIP"), Instant.now()));
        List<String> fields = new ArrayList<>();
        topicStat("lh.effects.v1").propertyNames().forEach(f -> fields.add(f.toLowerCase()));
        assertThat(fields).as("un campo di ritardo (lag/delay)").anyMatch(f -> f.contains("lag") || f.contains("delay"));
    }

    @Test
    @DisplayName("[TB-INS-PIP-008] per servizio: ultimo fatto prodotto (insight §3)")
    void perService() {
        String ev = uid("EVT-PIP");
        publish("lh.facts.v1", envelope(ev, FACT + "wallet.points.earned", "urn:loyaltyhub:service:wallet",
                "member:MBR-000002", uid("COR"), null, Instant.now(), Map.of("amount", 1, "currency", "PTS")));
        awaitStored(ev);
        JsonNode body = get("/v1/pipeline/status");
        assertThat(body.path("services").isArray()).as("elenco per servizio").isTrue();
        assertThat(body.path("services").toString()).contains("wallet");
    }

    @Test
    @DisplayName("[TB-INS-PIP-009] un evento su ognuno dei 5 topic: tutti e 5 presenti nello stato pipeline")
    void allTopics() {
        String cor = uid("COR-PIP");
        publish("lh.actions.v1", envelope(uid("EVT"), ACTION + "app.login.daily", "urn:loyaltyhub:source:app",
                "member:MBR-000002", cor, null, Instant.now(), Map.of()));
        effect(uid("EVT"), Instant.now());
        publish("lh.facts.v1", envelope(uid("EVT"), FACT + "tier.retained", "urn:loyaltyhub:service:wallet",
                "member:MBR-000002", cor, null, Instant.now(), Map.of()));
        ObjectNode audit = envelope(uid("EVT"), AUDIT_TYPE, "urn:loyaltyhub:service:wallet", "WALLET:MBR-000002", cor,
                null, Instant.now(), Map.of("service", "wallet", "entityType", "WALLET", "entityId", "MBR-000002",
                        "action", "ADJUST", "summary", "x"));
        publish("lh.audit.v1", audit);
        String dlqEv = uid("EVT");
        publish("lh.dlq.v1", "k", mapper.writeValueAsString(envelope(dlqEv, ACTION + "app.login.daily",
                "urn:loyaltyhub:source:app", "member:MBR-000002", cor, null, Instant.now(), Map.of())),
                Map.of("lh-original-topic", "lh.actions.v1", "lh-consumer", "lh-tb-pip-009"), null);
        await("5 topic", () -> {
            List<String> names = new ArrayList<>();
            get("/v1/pipeline/status").path("topics").forEach(t -> names.add(t.path("topic").asString()));
            return names.containsAll(List.of("lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"));
        }, 20_000);
    }

    // ---------- ING: ingest ----------

    private JsonNode ingestOne(String topic, String type, String source, String subject, Map<String, ?> data) {
        String ev = uid("EVT-ING");
        publish(topic, envelope(ev, type, source, subject, uid("COR-ING"), null, Instant.now(), data));
        awaitStored(ev);
        return get("/v1/events/" + ev);
    }

    @Test
    @DisplayName("[TB-INS-ING-001] azione su lh.actions.v1: famiglia ACTION, tipo breve senza prefisso, membro dal subject")
    void ingestAction() {
        JsonNode d = ingestOne("lh.actions.v1", ACTION + "purchase.completed", "urn:loyaltyhub:source:ecommerce",
                "member:MBR-000003", Map.of("amount", 130));
        assertThat(d.path("family").asString()).isEqualTo("ACTION");
        assertThat(d.path("shortType").asString()).isEqualTo("purchase.completed");
        assertThat(d.path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(d.path("topic").asString()).isEqualTo("lh.actions.v1");
    }

    @Test
    @DisplayName("[TB-INS-ING-002] effetto su lh.effects.v1: famiglia EFFECT")
    void ingestEffect() {
        JsonNode d = ingestOne("lh.effects.v1", EFFECT + "message.send", "urn:loyaltyhub:service:campaign",
                "member:MBR-000003", Map.of("templateCode", "MSG-X"));
        assertThat(d.path("family").asString()).isEqualTo("EFFECT");
        assertThat(d.path("shortType").asString()).isEqualTo("message.send");
    }

    @Test
    @DisplayName("[TB-INS-ING-003] fatto su lh.facts.v1: famiglia FACT")
    void ingestFact() {
        JsonNode d = ingestOne("lh.facts.v1", FACT + "coupon.used", "urn:loyaltyhub:service:reward",
                "member:MBR-000003", Map.of("couponCode", "C"));
        assertThat(d.path("family").asString()).isEqualTo("FACT");
        assertThat(d.path("shortType").asString()).isEqualTo("coupon.used");
    }

    @Test
    @DisplayName("[TB-INS-ING-004] audit su lh.audit.v1: famiglia AUDIT, tipo breve «entry»")
    void ingestAudit() {
        JsonNode d = ingestOne("lh.audit.v1", AUDIT_TYPE, "urn:loyaltyhub:service:member", "MEMBER:MBR-000003",
                Map.of("service", "member", "entityType", "MEMBER", "entityId", "MBR-000003", "action", "UPDATE",
                        "summary", "x"));
        assertThat(d.path("family").asString()).isEqualTo("AUDIT");
        assertThat(d.path("shortType").asString()).isEqualTo("entry");
    }

    @Test
    @DisplayName("[TB-INS-ING-005] tipo fuori da io.loyaltyhub.* (ramo senza specifica): tipo breve = tipo intero")
    void ingestForeignType() {
        JsonNode d = ingestOne("lh.facts.v1", "com.example.qualcosa", "urn:altro", "member:MBR-000003", Map.of());
        assertThat(d.path("shortType").asString()).isEqualTo("com.example.qualcosa");
    }

    @Test
    @DisplayName("[TB-INS-ING-006] subject non di membro (fatto di configurazione): nessun memberId")
    void ingestConfigSubject() {
        JsonNode d = ingestOne("lh.facts.v1", FACT + "campaign.status.changed", "urn:loyaltyhub:service:campaign",
                "CAMPAIGN:CMP-X", Map.of("newStatus", "LIVE"));
        assertThat(d.path("memberId").isMissingNode() || d.path("memberId").isNull()).isTrue();
    }

    @Test
    @DisplayName("[TB-INS-ING-007] attributi conservati: source, correlationId, causationId, hop, actor, time di business")
    void ingestAttributes() {
        String ev = uid("EVT-ING");
        String cor = uid("COR-ING");
        ObjectNode env = envelope(ev, FACT + "badge.awarded", "urn:loyaltyhub:service:gamification", "member:MBR-000004",
                cor, "EVT-PADRE", Instant.parse("2026-09-10T08:00:00Z"), Map.of("badgeCode", "BDG-TRIS"));
        env.put("lhhop", 1);
        env.put("lhactor", "MARKETING:luca.marketing");
        publish("lh.facts.v1", env);
        awaitStored(ev);
        JsonNode d = get("/v1/events/" + ev);
        assertThat(d.path("source").asString()).isEqualTo("urn:loyaltyhub:service:gamification");
        assertThat(d.path("correlationId").asString()).isEqualTo(cor);
        assertThat(d.path("causationId").asString()).isEqualTo("EVT-PADRE");
        assertThat(d.path("hop").asInt()).isEqualTo(1);
        assertThat(d.path("actor").asString()).isEqualTo("MARKETING:luca.marketing");
        assertThat(d.path("eventTime").asString()).isEqualTo("2026-09-10T08:00:00Z");
    }

    @Test
    @DisplayName("[TB-INS-ING-008] evento duplicato sul topic: una sola riga e un solo messaggio nel flusso live")
    void ingestDuplicateLive() throws Exception {
        String cor = uid("COR-ING");
        String ev = uid("EVT-ING");
        try (SseClient sse = new SseClient("?correlationId=" + cor, null, Map.of())) {
            ObjectNode env = envelope(ev, ACTION + "app.login.daily", "urn:loyaltyhub:source:app", "member:MBR-000002",
                    cor, null, Instant.now(), Map.of());
            publish("lh.actions.v1", env);
            publish("lh.actions.v1", env);
            String sentinel = uid("EVT-ING");
            publish("lh.actions.v1", envelope(sentinel, ACTION + "app.login.daily", "urn:loyaltyhub:source:app",
                    "member:MBR-000002", cor, null, Instant.now(), Map.of()));
            awaitStored(sentinel);
            List<SseEvent> got = sse.take(2, 10_000);
            got.addAll(sse.drain(500));
            assertThat(got).extracting(SseEvent::id).containsExactly(ev, sentinel);
            assertThat(get("/v1/events?correlationId=" + cor).path("items").size()).isEqualTo(2);
        }
    }

    // ---------- ROL: letture ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/ruoli-lettura.csv", numLinesToSkip = 1)
    void letture(String id, String desc, String path, String role, int expHttp) {
        assertThat(call("GET", path, actor(role), null).status()).isEqualTo(expHttp);
    }
}
