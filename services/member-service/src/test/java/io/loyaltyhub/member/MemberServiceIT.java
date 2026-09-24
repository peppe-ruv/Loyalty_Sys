package io.loyaltyhub.member;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * member-service M1.2 (docs/servizi/member-service.md §7): anagrafica, ricerca, stati, i tre fatti
 * {@code member.registered/updated/status.changed} e la proiezione che riflette {@code wallet.points.earned}.
 * Col profilo {@code demo} i 12 membri sono caricati dai seed. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberServiceIT {

    private static final String FACTS = "lh.facts.v1";
    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=member");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void seedLoadsTwelveMembersAndPersonas() {
        JsonNode page = get("/v1/members?size=50");
        assertThat(page.path("page").path("totalItems").asInt()).isEqualTo(12);

        JsonNode personas = get("/v1/demo/personas");
        assertThat(personas.size()).isEqualTo(11); // l'anonimizzato non è selezionabile
        assertThat(personas.get(0).path("story").asString()).isNotBlank();
    }

    @Test
    void searchFiltersByTierStatusAndQuery() {
        assertThat(get("/v1/members?tier=GOLD").path("page").path("totalItems").asInt()).isEqualTo(3);
        assertThat(get("/v1/members?status=BLOCKED").path("page").path("totalItems").asInt()).isEqualTo(1);
        assertThat(get("/v1/members?q=marco").path("page").path("totalItems").asInt()).isEqualTo(1);
    }

    @Test
    void createEmitsRegisteredFactWithSnapshot() {
        Map<String, Object> req = Map.of(
                "firstName", "Nuovo", "lastName", "Socio", "email", "nuovo.socio@example.org",
                "city", "Milano", "channel", "PORTAL");
        JsonNode created = post("/v1/members", req, 201);
        String id = created.path("id").asString();
        assertThat(id).startsWith("MBR-");
        assertThat(created.path("referralCode").asString()).hasSize(8);
        assertThat(created.path("tier").asString()).isEqualTo("BASE");

        try (KafkaConsumer<String, String> consumer = consumer("reg-check")) {
            consumer.subscribe(List.of(FACTS));
            ConsumerRecord<String, String> rec = poll(consumer,
                    r -> r.key().equals(id) && readJson(r.value()).path("type").asString()
                            .equals("io.loyaltyhub.fact.member.registered"));
            assertThat(rec).as("fatto member.registered pubblicato").isNotNull();
            JsonNode data = readJson(rec.value()).path("data");
            assertThat(data.path("memberId").asString()).isEqualTo(id);
            assertThat(data.path("email").asString()).isEqualTo("nuovo.socio@example.org");
            assertThat(data.path("referralCode").asString()).hasSize(8);
        }
    }

    @Test
    void patchBumpsVersionAndEmitsUpdatedFact() {
        Map<String, Object> req = Map.of("version", 0, "city", "Torino", "phone", "+39 011 000000");
        JsonNode updated = patch("/v1/members/MBR-000001", req);
        assertThat(updated.path("version").asInt()).isEqualTo(1);
        assertThat(updated.path("city").asString()).isEqualTo("Torino");

        try (KafkaConsumer<String, String> consumer = consumer("upd-check")) {
            consumer.subscribe(List.of(FACTS));
            ConsumerRecord<String, String> rec = poll(consumer,
                    r -> r.key().equals("MBR-000001") && readJson(r.value()).path("type").asString()
                            .equals("io.loyaltyhub.fact.member.updated"));
            assertThat(rec).as("fatto member.updated pubblicato").isNotNull();
        }
    }

    @Test
    void versionConflictIsRejected() {
        try {
            client().patch().uri("/v1/members/MBR-000003").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("version", 99, "city", "Roma")).retrieve().toEntity(JsonNode.class);
            fail("atteso 409");
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
            assertThat(e.getResponseBodyAs(JsonNode.class).path("code").asString()).isEqualTo("VERSION_CONFLICT");
        }
    }

    @Test
    void statusChangeEmitsStatusChangedFact() {
        JsonNode changed = post("/v1/members/MBR-000010/status", Map.of("status", "BLOCKED", "reason", "test"), 200);
        assertThat(changed.path("status").asString()).isEqualTo("BLOCKED");

        try (KafkaConsumer<String, String> consumer = consumer("status-check")) {
            consumer.subscribe(List.of(FACTS));
            ConsumerRecord<String, String> rec = poll(consumer,
                    r -> r.key().equals("MBR-000010") && readJson(r.value()).path("type").asString()
                            .equals("io.loyaltyhub.fact.member.status.changed"));
            assertThat(rec).as("fatto member.status.changed pubblicato").isNotNull();
            JsonNode data = readJson(rec.value()).path("data");
            assertThat(data.path("previousStatus").asString()).isEqualTo("ACTIVE");
            assertThat(data.path("newStatus").asString()).isEqualTo("BLOCKED");
        }
    }

    @Test
    void projectionReflectsWalletPointsEarned() {
        publishFact("io.loyaltyhub.fact.wallet.points.earned", "member:MBR-000011",
                Map.of("balanceAfter", 7777, "points", 100));

        long deadline = System.currentTimeMillis() + 15_000;
        long balance = -1;
        while (System.currentTimeMillis() < deadline) {
            balance = get("/v1/members/MBR-000011").path("balancePts").asLong();
            if (balance == 7777) {
                break;
            }
            sleep();
        }
        assertThat(balance).as("balance_pts riflette balanceAfter del fatto wallet.points.earned").isEqualTo(7777);
    }

    // ---------- helper ----------

    @Test
    void attributesAndLabelsAreValidatedAndTravelInTheSnapshot() {
        JsonNode defs = get("/v1/attribute-definitions");
        assertThat(defs).hasSize(4);
        assertThat(defs.toString()).contains("preferredChannel", "householdSize", "\"APP\"");
        JsonNode matteo = get("/v1/members/MBR-000010");
        assertThat(matteo.path("attributes").path("preferredChannel").asString()).isEqualTo("APP");
        assertThat(matteo.path("attributes").has("story")).as("la storia della persona non è un attributo").isFalse();

        assertThat(fieldsOf(send("PATCH", "/v1/members/MBR-000010", "CARE:paolo.care",
                Map.of("attributes", Map.of("householdSize", "tre")), 422))).containsExactly("attributes.householdSize");
        assertThat(fieldsOf(send("PATCH", "/v1/members/MBR-000010", "CARE:paolo.care",
                Map.of("attributes", Map.of("shoeSize", 42)), 422))).containsExactly("attributes.shoeSize");
        assertThat(fieldsOf(send("PATCH", "/v1/members/MBR-000010", "CARE:paolo.care",
                Map.of("attributes", Map.of("preferredChannel", "FAX")), 422))).containsExactly("attributes.preferredChannel");
        assertThat(fieldsOf(send("PATCH", "/v1/members/MBR-000010", "CARE:paolo.care",
                Map.of("labels", List.of("ok", "non valida!")), 422))).containsExactly("labels");

        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("householdSize", 4);
        attrs.put("hasGasContract", null);
        try (KafkaConsumer<String, String> consumer = consumer("attr-check")) {
            consumer.subscribe(List.of(FACTS));
            JsonNode updated = send("PATCH", "/v1/members/MBR-000010", "CARE:paolo.care",
                    Map.of("attributes", attrs, "labels", List.of("VIP ", "vip", "newsletter")), 200);
            assertThat(updated.path("attributes").path("householdSize").asInt()).isEqualTo(4);
            assertThat(updated.path("attributes").has("hasGasContract")).isFalse();
            assertThat(updated.path("attributes").path("preferredChannel").asString()).isEqualTo("APP");
            assertThat(updated.path("labels").toString()).isEqualTo("[\"vip\",\"newsletter\"]");

            ConsumerRecord<String, String> rec = poll(consumer,
                    r -> r.key().equals("MBR-000010") && readJson(r.value()).path("type").asString()
                            .equals("io.loyaltyhub.fact.member.updated")
                            && readJson(r.value()).path("data").path("attributes").path("householdSize").asInt() == 4);
            assertThat(rec).as("member.updated con gli attributi").isNotNull();
            JsonNode data = readJson(rec.value()).path("data");
            assertThat(data.path("attributes").has("story")).isFalse();
            assertThat(data.path("birthDate").asString()).isEqualTo("2000-07-19");
            assertThat(data.path("labels").toString()).contains("vip", "newsletter");
        }
        // La storia della persona resta (chiave interna).
        assertThat(get("/v1/demo/personas").toString()).contains("Ha 3 giocate da usare");
    }

    @Test
    void attributeDefinitionsAreReplacedWithRolesAndInUseGuard() {
        List<Map<String, Object>> current = new java.util.ArrayList<>();
        get("/v1/attribute-definitions").forEach(d -> current.add(mapper.convertValue(d, Map.class)));

        send("PUT", "/v1/attribute-definitions", "ANALYST:sara.analyst", current, 403);
        send("PUT", "/v1/attribute-definitions", "CARE:paolo.care", current, 403);

        List<Map<String, Object>> withoutUsed = current.stream()
                .filter(d -> !"preferredChannel".equals(d.get("key"))).toList();
        assertThat(send("PUT", "/v1/attribute-definitions", "MARKETING:luca.marketing", withoutUsed, 409)
                .path("code").asString()).isEqualTo("ATTRIBUTE_IN_USE");

        List<Map<String, Object>> invalid = new java.util.ArrayList<>(current);
        invalid.add(Map.of("key", "Bad Key", "label", "", "type", "COLOR", "options", List.of()));
        assertThat(fieldsOf(send("PUT", "/v1/attribute-definitions", "MARKETING:luca.marketing", invalid, 422)))
                .contains("[4].key", "[4].label", "[4].type");

        List<Map<String, Object>> extended = new java.util.ArrayList<>(current);
        extended.add(Map.of("key", "contractType", "label", "Tipo di contratto", "type", "STRING",
                "options", List.of("LUCE", "GAS", "DUAL")));
        JsonNode saved = send("PUT", "/v1/attribute-definitions", "MARKETING:luca.marketing", extended, 200);
        assertThat(saved).hasSize(5);
        assertThat(saved.get(4).path("key").asString()).isEqualTo("contractType");
        JsonNode patched = send("PATCH", "/v1/members/MBR-000007", "ADMIN:marta.admin",
                Map.of("attributes", Map.of("contractType", "DUAL")), 200);
        assertThat(patched.path("attributes").path("contractType").asString()).isEqualTo("DUAL");
    }

    private JsonNode get(String path) {
        return client().get().uri(path).retrieve().body(JsonNode.class);
    }

    private JsonNode post(String path, Object body, int expected) {
        ResponseEntity<JsonNode> res = client().post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(expected);
        return res.getBody();
    }

    private JsonNode patch(String path, Object body) {
        return client().patch().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("X-LH-Actor", actor);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private static List<String> fieldsOf(JsonNode problem) {
        List<String> out = new java.util.ArrayList<>();
        problem.path("errors").forEach(e -> out.add(e.path("field").asString()));
        return out;
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private void publishFact(String type, String subject, Map<String, Object> data) {
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", "test-" + System.nanoTime(),
                "source", "urn:loyaltyhub:service:wallet", "type", type, "subject", subject,
                "time", Instant.now().toString(), "lhcorrelationid", "test-corr", "lhhop", 0, "data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(FACTS, subject.substring("member:".length()),
                    mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer,
                                                Predicate<ConsumerRecord<String, String>> match) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    private JsonNode readJson(String value) {
        return mapper.readTree(value);
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
