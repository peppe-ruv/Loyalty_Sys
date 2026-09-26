package io.loyaltyhub.insight;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * insight-service M2.1 (docs/servizi/insight-service.md §7): ingest di tutti i topic nell'event store,
 * idempotenza su {@code event_id}, retention. Senza Docker: EmbeddedKafka + Zonky, profilo demo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightServiceIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void storesEventsFromAllTopicsWithFamilyAndIsIdempotent() {
        String corr = "COR-INS-1";
        publish("lh.actions.v1", envelope("EVT-A1", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "member:MBR-000003", corr));
        publish("lh.effects.v1", envelope("EVT-E1", "io.loyaltyhub.effect.points.grant",
                "urn:loyaltyhub:service:campaign", "member:MBR-000003", corr));
        publish("lh.facts.v1", envelope("EVT-F1", "io.loyaltyhub.fact.wallet.points.earned",
                "urn:loyaltyhub:service:wallet", "member:MBR-000003", corr));
        // Duplicato dell'azione: non deve creare una seconda riga.
        publish("lh.actions.v1", envelope("EVT-A1", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "member:MBR-000003", corr));

        JsonNode page = awaitCount(corr, 3);
        assertThat(page.path("page").path("totalItems").asInt()).isEqualTo(3);

        // L'azione è registrata con la famiglia del topic e lo short type senza prefisso.
        JsonNode detail = client().get().uri("/v1/events/EVT-A1").retrieve().body(JsonNode.class);
        assertThat(detail.path("family").asString()).isEqualTo("ACTION");
        assertThat(detail.path("shortType").asString()).isEqualTo("purchase.completed");
        assertThat(detail.path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(detail.path("payload").path("data").path("amount").asInt()).isEqualTo(130);

        // L'idempotenza (duplicato non raddoppiato) è già provata sopra: la pagina per COR-INS-1 ha 3 righe,
        // non 4. La statistica del topic azioni esiste ed è positiva (il valore globale dipende dagli altri test).
        JsonNode pipeline = client().get().uri("/v1/pipeline/status").retrieve().body(JsonNode.class);
        long actionCount = 0;
        for (JsonNode t : pipeline.path("topics")) {
            if (t.path("topic").asString().equals("lh.actions.v1")) {
                actionCount = t.path("countTotal").asLong();
            }
        }
        assertThat(actionCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void demoResetClearsTheStore() {
        publish("lh.facts.v1", envelope("EVT-R1", "io.loyaltyhub.fact.wallet.points.earned",
                "urn:loyaltyhub:service:wallet", "member:MBR-000009", "COR-RST"));
        awaitCount("COR-RST", 1);

        JsonNode body = client().post().uri("/v1/demo/reset").header("X-LH-Actor", "ADMIN:test")
                .retrieve().body(JsonNode.class);
        assertThat(body.path("status").asString()).isEqualTo("OK");

        JsonNode page = client().get().uri("/v1/events?correlationId=COR-RST").retrieve().body(JsonNode.class);
        assertThat(page.path("page").path("totalItems").asInt()).isEqualTo(0);
    }

    @Test
    void traceOutcomeReadsTierChangeFromTheFactContract() {
        // EVT-FACT-28 (docs/05): {previousTier, newTier, periodSts}.
        publish("lh.facts.v1", env("EVT-TU", "io.loyaltyhub.fact.tier.upgraded",
                "urn:loyaltyhub:service:wallet", "member:MBR-000003", "COR-TU", null,
                Map.of("previousTier", "SILVER", "newTier", "GOLD", "periodSts", 3010)));
        JsonNode trace = awaitTrace("COR-TU", 1);
        assertThat(trace.path("outcome").path("tierChange").path("from").asString()).isEqualTo("SILVER");
        assertThat(trace.path("outcome").path("tierChange").path("to").asString()).isEqualTo("GOLD");
    }

    @Test
    void buildsTraceTreeWithOutcome() {
        // Catena azione → effetto → fatto con lo stesso correlationId e causazione a cascata.
        publish("lh.actions.v1", env("EVT-TA", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "member:MBR-000003", "COR-TR", null,
                Map.of("orderId", "ORD-9", "amount", 130)));
        publish("lh.effects.v1", env("EVT-TE", "io.loyaltyhub.effect.points.grant",
                "urn:loyaltyhub:service:campaign", "member:MBR-000003", "COR-TR", "EVT-TA",
                Map.of("amount", 130, "currency", "PTS")));
        publish("lh.facts.v1", env("EVT-TF", "io.loyaltyhub.fact.wallet.points.earned",
                "urn:loyaltyhub:service:wallet", "member:MBR-000003", "COR-TR", "EVT-TE",
                Map.of("amount", 162, "currency", "PTS")));

        JsonNode trace = awaitTrace("COR-TR", 3);
        assertThat(trace.path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(trace.path("nodes").size()).isEqualTo(3);

        // La radice è l'azione (parentEventId assente), il fatto ha come genitore l'effetto.
        JsonNode root = null;
        JsonNode fact = null;
        for (JsonNode n : trace.path("nodes")) {
            if (n.path("eventId").asString().equals("EVT-TA")) root = n;
            if (n.path("eventId").asString().equals("EVT-TF")) fact = n;
        }
        assertThat(root).isNotNull();
        assertThat(root.path("parentEventId").isMissingNode() || root.path("parentEventId").isNull()).isTrue();
        assertThat(root.path("shortType").asString()).isEqualTo("purchase.completed");
        assertThat(fact.path("parentEventId").asString()).isEqualTo("EVT-TE");
        assertThat(fact.path("service").asString()).isEqualTo("wallet");

        // Esito: +162 PTS accreditati.
        JsonNode points = trace.path("outcome").path("points");
        assertThat(points.size()).isEqualTo(1);
        assertThat(points.get(0).path("currency").asString()).isEqualTo("PTS");
        assertThat(points.get(0).path("amount").asLong()).isEqualTo(162);
    }

    @Test
    void streamDeliversLiveEventOverSse() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/stream/events?topics=lh.actions.v1"))
                .header("Accept", "text/event-stream").GET().build();

        // send() torna appena arrivano gli header: da qui l'emitter è registrato sul LiveEventHub.
        HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type").orElse("")).contains("text/event-stream");

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.offer(line);
                }
            } catch (Exception ignored) {
                // stream chiuso a fine test
            }
        });
        reader.setDaemon(true);
        reader.start();

        publish("lh.actions.v1", envelope("EVT-SSE-1", "io.loyaltyhub.action.purchase.completed",
                "urn:loyaltyhub:source:ecommerce", "member:MBR-000003", "COR-SSE"));

        StringBuilder acc = new StringBuilder();
        boolean seen = false;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(500, TimeUnit.MILLISECONDS);
            if (line != null) {
                acc.append(line).append('\n');
                if (acc.indexOf("lh-event") >= 0 && acc.indexOf("EVT-SSE-1") >= 0) {
                    seen = true;
                    break;
                }
            }
        }
        reader.interrupt();
        assertThat(seen).as("l'evento arriva sul canale SSE con nome lh-event").isTrue();
    }

    @Test
    void syntheticHistoryFillsNinetyDaysOfKpis() {
        // F-INS-04 (docs §5, §7): al seed lo storico sintetico riempie 90 giorni di metric_daily.
        JsonNode series = client().get().uri("/v1/kpi/timeseries?metric=points_earned&days=90")
                .retrieve().body(JsonNode.class);
        JsonNode points = series.path("points");
        assertThat(points.size()).isGreaterThanOrEqualTo(85);
        // Nessun grafico vuoto e ogni giorno storico è marcato synthetic (docs/08 §BO-01).
        boolean allSynthetic = true;
        double sum = 0;
        for (JsonNode p : points) {
            allSynthetic &= p.path("synthetic").asBoolean(false);
            sum += p.path("value").asDouble(0);
        }
        assertThat(allSynthetic).as("i giorni dello storico sono synthetic").isTrue();
        assertThat(sum).isGreaterThan(0);
    }

    @Test
    void kpiOverviewHasBaselineAndDeltas() {
        JsonNode o = client().get().uri("/v1/kpi/overview?days=90").retrieve().body(JsonNode.class);
        assertThat(o.path("pointsEarned").asLong()).isGreaterThan(0);
        assertThat(o.path("actions").asLong()).isGreaterThan(0);
        assertThat(o.path("membersActive30d").asLong()).isGreaterThan(0);
        assertThat(o.path("membersTotal").asLong()).isGreaterThanOrEqualTo(12);
        // Il delta vs periodo precedente esiste (può essere positivo o negativo).
        assertThat(o.path("deltas").path("pointsEarned").has("abs")).isTrue();
    }

    @Test
    void kpiBreakdownSplitsActionsBySource() {
        JsonNode b = client().get().uri("/v1/kpi/breakdown?metric=actions&dimension=source&days=90&limit=5")
                .retrieve().body(JsonNode.class);
        assertThat(b.path("total").asLong()).isGreaterThan(0);
        boolean hasEcommerce = false;
        for (JsonNode s : b.path("slices")) {
            if (s.path("dimValue").asString().equals("ecommerce")) hasEcommerce = true;
        }
        assertThat(hasEcommerce).as("la ripartizione per fonte include ecommerce").isTrue();
    }

    @Test
    void recordsAuditEntryFromAuditTopic() {
        // Voce di audit su lh.audit.v1: attore obbligatorio, before/after coi soli campi cambiati (docs/05 §6).
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "member");
        data.put("entityType", "MEMBER");
        data.put("entityId", "MBR-000007");
        data.put("action", "UPDATE");
        data.put("summary", "Modificato profilo (1 campo)");
        data.put("before", Map.of("city", "Roma"));
        data.put("after", Map.of("city", "Milano"));
        publish("lh.audit.v1", auditEnvelope("EVT-AUD-1", "MEMBER:MBR-000007", "ADMIN:giuseppe", "COR-AUD", data));
        // Duplicato dello stesso evento: una sola voce.
        publish("lh.audit.v1", auditEnvelope("EVT-AUD-1", "MEMBER:MBR-000007", "ADMIN:giuseppe", "COR-AUD", data));

        JsonNode page = awaitAudit("MBR-000007", 1);
        assertThat(page.path("items").size()).isEqualTo(1); // idempotente su event_id
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("action").asString()).isEqualTo("UPDATE");
        assertThat(item.path("service").asString()).isEqualTo("member");
        assertThat(item.path("actorRole").asString()).isEqualTo("ADMIN");
        assertThat(item.path("actorName").asString()).isEqualTo("giuseppe");
        assertThat(item.path("after").path("city").asString()).isEqualTo("Milano");
        assertThat(item.path("before").path("city").asString()).isEqualTo("Roma");

        // Dettaglio per id con il diff completo.
        String id = item.path("id").asString();
        JsonNode detail = client().get().uri("/v1/audit/" + id).retrieve().body(JsonNode.class);
        assertThat(detail.path("entityId").asString()).isEqualTo("MBR-000007");
        assertThat(detail.path("correlationId").asString()).isEqualTo("COR-AUD");
    }

    /**
     * Doppia lettura {@code member.*:1}/{@code :2} (ADR-032, Q-346, docs/18 §3.4): la {@code :2} (senza nome, cognome,
     * soprannome, e-mail, data di nascita, città; con locale, birthYear, province, emailHash) si registra, conta nei
     * nuovi membri come la {@code :1}, ha la stessa sintesi e all'anonimizzazione perde {@code emailHash}; la copia
     * {@code :1} perde le chiavi personali anche se l'anonimizzazione arriva come {@code member.updated:2}.
     */
    @Test
    void memberFactsV1AndV2AreStoredCountedSummarizedAndRedacted() {
        String hash = "21cebef191120423981adfc0ba20d56791dd828ae5fe4bf3c5ab840bcc9deacb";
        String day = "2031-01-15";
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("memberId", "MBR-000901");
        v1.put("firstName", "Ottavia");
        v1.put("lastName", "Brunelli");
        v1.put("nickname", "otta.b");
        v1.put("email", "ottavia.brunelli@example.test");
        v1.put("externalId", "CRM-9901");
        v1.put("status", "ACTIVE");
        v1.put("birthDate", "1990-02-03");
        v1.put("city", "Pavia");
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("memberId", "MBR-000902");
        v2.put("emailHash", hash);
        v2.put("status", "ACTIVE");
        v2.put("channel", "APP");
        v2.put("locale", "it");
        v2.put("birthYear", 1991);
        v2.put("province", "PV");
        v2.put("referredBy", null);
        v2.put("labels", java.util.List.of("early-adopter"));
        publish("lh.facts.v1", memberEnv("EVT-M1-REG", "registered", "MBR-000901", "COR-M12", 1, day, v1));
        publish("lh.facts.v1", memberEnv("EVT-M2-REG", "registered", "MBR-000902", "COR-M12", 2, day, v2));

        // Stessa sintesi per le due versioni, mai "null" (il tracciato usa EventSummaries come il rail).
        JsonNode trace = awaitTrace("COR-M12", 2);
        assertThat(trace.path("nodes").size()).isEqualTo(2);
        for (JsonNode n : trace.path("nodes")) {
            assertThat(n.path("summary").asString()).isEqualTo("Nuovo membro");
        }
        // I nuovi membri contano entrambe le versioni.
        JsonNode series = client().get()
                .uri("/v1/kpi/timeseries?metric=members_new&from=" + day + "&to=" + day)
                .retrieve().body(JsonNode.class);
        long membersNew = 0;
        for (JsonNode p : series.path("points")) {
            membersNew += p.path("value").asLong(0);
        }
        assertThat(membersNew).isEqualTo(2);
        JsonNode storedV2 = awaitEvent("EVT-M2-REG", d -> d.path("emailHash").asString("").equals(hash));
        assertThat(storedV2.path("province").asString()).isEqualTo("PV");

        // Anonimizzazione con member.updated:2 (status ANONYMIZED) per entrambi.
        Map<String, Object> anon1 = new LinkedHashMap<>();
        anon1.put("memberId", "MBR-000901");
        anon1.put("status", "ANONYMIZED");
        Map<String, Object> anon2 = new LinkedHashMap<>();
        anon2.put("memberId", "MBR-000902");
        anon2.put("status", "ANONYMIZED");
        anon2.put("birthYear", null);
        anon2.put("province", null);
        publish("lh.facts.v1", memberEnv("EVT-M1-ANON", "updated", "MBR-000901", "COR-M12-ANON", 2, day, anon1));
        publish("lh.facts.v1", memberEnv("EVT-M2-ANON", "updated", "MBR-000902", "COR-M12-ANON", 2, day, anon2));

        // Copia :1: senza chiavi personali, id e stato restano.
        JsonNode redactedV1 = awaitEvent("EVT-M1-REG", d -> !d.has("firstName"));
        for (String k : new String[]{"firstName", "lastName", "nickname", "email", "externalId", "birthDate", "city"}) {
            assertThat(redactedV1.has(k)).as(k).isFalse();
        }
        assertThat(redactedV1.path("memberId").asString()).isEqualTo("MBR-000901");
        assertThat(redactedV1.toString()).doesNotContain("Ottavia", "Brunelli", "ottavia.brunelli");
        // Copia :2: senza emailHash, restano i campi non personali; nessun errore di consumo.
        JsonNode redactedV2 = awaitEvent("EVT-M2-REG", d -> !d.has("emailHash"));
        assertThat(redactedV2.has("emailHash")).isFalse();
        assertThat(redactedV2.path("province").asString()).isEqualTo("PV");
        assertThat(redactedV2.path("birthYear").asInt()).isEqualTo(1991);
        assertThat(redactedV2.path("locale").asString()).isEqualTo("it");
        JsonNode anonTrace = awaitTrace("COR-M12-ANON", 2);
        for (JsonNode n : anonTrace.path("nodes")) {
            assertThat(n.path("family").asString()).isEqualTo("FACT");
            assertThat(n.path("summary").asString()).isEqualTo("Membro aggiornato");
        }
    }

    // ---------- helper ----------

    /** {@code data} dell'evento registrato quando soddisfa {@code ready} (entro 20 s), altrimenti l'ultimo letto. */
    private JsonNode awaitEvent(String eventId, java.util.function.Predicate<JsonNode> ready) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode data = null;
        while (System.currentTimeMillis() < deadline) {
            JsonNode detail = client().get().uri("/v1/events/" + eventId).exchange((req, res) ->
                    res.getStatusCode().value() == 200 ? new ObjectMapper().readTree(res.getBody()) : null);
            if (detail != null) {
                data = detail.path("payload").path("data");
                if (ready.test(data)) {
                    return data;
                }
            }
            sleep();
        }
        assertThat(data).as("evento " + eventId + " registrato").isNotNull();
        return data;
    }

    /** Fatto {@code member.<name>} di member-service con {@code dataschema} alla versione {@code version}. */
    private String memberEnv(String id, String name, String memberId, String correlationId, int version, String day,
                             Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", "urn:loyaltyhub:service:member");
        e.put("type", "io.loyaltyhub.fact.member." + name);
        e.put("subject", "member:" + memberId);
        e.put("time", day + "T10:00:00Z");
        e.put("datacontenttype", "application/json");
        e.put("dataschema", "urn:loyaltyhub:schema:fact.member." + name + ":" + version);
        e.put("lhtenant", "aurora");
        e.put("lhcorrelationid", correlationId);
        e.put("lhhop", 0);
        e.put("lhactor", "system");
        e.put("data", data);
        return mapper.writeValueAsString(e);
    }

    private JsonNode awaitAudit(String entityId, int expected) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode page = null;
        while (System.currentTimeMillis() < deadline) {
            page = client().get().uri("/v1/audit?entityId=" + entityId).retrieve().body(JsonNode.class);
            if (page != null && page.path("items").size() >= expected) {
                return page;
            }
            sleep();
        }
        return page;
    }

    private String auditEnvelope(String id, String subject, String actor, String correlationId,
                                 Map<String, Object> data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("specversion", "1.0");
        env.put("id", id);
        env.put("source", "urn:loyaltyhub:service:member");
        env.put("type", "io.loyaltyhub.audit.entry");
        env.put("subject", subject);
        env.put("time", "2026-09-15T10:00:00Z");
        env.put("lhcorrelationid", correlationId);
        env.put("lhhop", 0);
        env.put("lhactor", actor);
        env.put("data", data);
        return mapper.writeValueAsString(env);
    }

    private JsonNode awaitCount(String correlationId, int expected) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode page = null;
        while (System.currentTimeMillis() < deadline) {
            page = client().get().uri("/v1/events?correlationId=" + correlationId).retrieve().body(JsonNode.class);
            if (page != null && page.path("page").path("totalItems").asInt() >= expected) {
                return page;
            }
            sleep();
        }
        return page;
    }

    private JsonNode awaitTrace(String correlationId, int expectedNodes) {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode trace = null;
        while (System.currentTimeMillis() < deadline) {
            trace = client().get().uri("/v1/traces/" + correlationId).exchange((req, res) -> res.getStatusCode().value() == 200
                    ? new tools.jackson.databind.ObjectMapper().readTree(res.getBody()) : null);
            if (trace != null && trace.path("nodes").size() >= expectedNodes) {
                return trace;
            }
            sleep();
        }
        return trace;
    }

    private String env(String id, String type, String source, String subject, String correlationId,
                       String causationId, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", source);
        e.put("type", type);
        e.put("subject", subject);
        e.put("time", "2026-09-15T10:00:00Z");
        e.put("lhcorrelationid", correlationId);
        if (causationId != null) {
            e.put("lhcausationid", causationId);
        }
        e.put("lhhop", 0);
        e.put("lhactor", "system");
        e.put("data", data);
        return mapper.writeValueAsString(e);
    }

    private String envelope(String id, String type, String source, String subject, String correlationId) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("specversion", "1.0");
        env.put("id", id);
        env.put("source", source);
        env.put("type", type);
        env.put("subject", subject);
        env.put("time", "2026-09-15T10:00:00Z");
        env.put("lhcorrelationid", correlationId);
        env.put("lhcausationid", correlationId);
        env.put("lhhop", 0);
        env.put("lhactor", "system");
        env.put("data", Map.of("orderId", "ORD-1", "amount", 130, "currency", "EUR"));
        return mapper.writeValueAsString(env);
    }

    private void publish(String topic, String value) {
        Properties props = new Properties();
        props.put("bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"));
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, "MBR-000003", value));
            producer.flush();
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
