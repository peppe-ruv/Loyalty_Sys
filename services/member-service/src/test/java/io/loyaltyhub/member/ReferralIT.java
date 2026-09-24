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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Referral, registrazione dal portale e completamento profilo (M5.6, docs/servizi/member-service.md §5, §7;
 * F-REF-01/02, F-MBR-06/07). Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferralIT {

    private static final String FACTS = "lh.facts.v1";
    private static final String ACTIONS = "lh.actions.v1";
    private static final String REFERRAL = "io.loyaltyhub.fact.referral.completed";
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

    /** docs §7: Elisa (invitata da Marco) al primo acquisto → due fatti con ruoli opposti, nessuno ai successivi. */
    @Test
    void firstQualifyingPurchaseCompletesReferralOnce() {
        String first = publishAction("purchase.completed", "MBR-000009", Map.of("orderId", "ORD-REF-1", "amount", 45));

        List<JsonNode> facts;
        try (KafkaConsumer<String, String> consumer = consumer("ref-check")) {
            consumer.subscribe(List.of(FACTS));
            facts = collect(consumer, r -> REFERRAL.equals(readJson(r.value()).path("type").asString()), 2);
        }
        assertThat(facts).hasSize(2);
        Map<String, JsonNode> byRole = new HashMap<>();
        for (JsonNode f : facts) {
            byRole.put(f.path("data").path("role").asString(), f);
            assertThat(f.path("lhcausationid").asString()).isEqualTo(first);
            assertThat(f.path("data").path("qualifyingActionId").asString()).isEqualTo(first);
        }
        assertThat(byRole.get("REFEREE").path("subject").asString()).isEqualTo("member:MBR-000009");
        assertThat(byRole.get("REFEREE").path("data").path("counterpartMemberId").asString()).isEqualTo("MBR-000002");
        assertThat(byRole.get("REFERRER").path("subject").asString()).isEqualTo("member:MBR-000002");
        assertThat(byRole.get("REFERRER").path("data").path("counterpartMemberId").asString()).isEqualTo("MBR-000009");
        assertThat(byRole.get("REFERRER").path("lhcorrelationid").asString())
                .isEqualTo(byRole.get("REFEREE").path("lhcorrelationid").asString());

        // Secondo acquisto: il legame è già completato, nessun altro fatto.
        publishAction("purchase.completed", "MBR-000009", Map.of("orderId", "ORD-REF-2", "amount", 20));
        try (KafkaConsumer<String, String> consumer = consumer("ref-check-2")) {
            consumer.subscribe(List.of(FACTS));
            List<JsonNode> all = collect(consumer, r -> REFERRAL.equals(readJson(r.value()).path("type").asString()), 3);
            assertThat(all).as("nessun terzo referral.completed").hasSize(2);
        }

        JsonNode portal = get("/v1/portal/members/MBR-000002/referral");
        assertThat(portal.path("completedCount").asInt()).isEqualTo(1);
        assertThat(portal.path("invited").get(0).path("nickname").asString()).isEqualTo("eli_f");
        assertThat(portal.path("invited").get(0).path("status").asString()).isEqualTo("COMPLETED");
        assertThat(portal.path("shareUrl").asString()).isEqualTo("/portal/join?ref=" + portal.path("code").asString());
    }

    @Test
    void registrationWithFriendCodeCreatesLinkAndInvalidCodeIs422() {
        String code = get("/v1/members/MBR-000003").path("referralCode").asString();
        JsonNode created = post("/v1/members", Map.of(
                "firstName", "Lia", "lastName", "Nuova", "email", "lia.nuova@example.org",
                "channel", "PORTAL", "referralCode", " " + code.toLowerCase() + " ",
                "consents", Map.of("marketing", true)), 201);
        assertThat(created.path("referredBy").asString()).isEqualTo("MBR-000003");
        assertThat(created.path("nickname").asString()).isEqualTo("Lia N.");

        JsonNode links = get("/v1/members/MBR-000003/referrals");
        assertThat(links.get(0).path("refereeId").asString()).isEqualTo(created.path("id").asString());
        assertThat(links.get(0).path("status").asString()).isEqualTo("PENDING");

        JsonNode profile = get("/v1/portal/members/" + created.path("id").asString());
        assertThat(profile.path("consents").path("marketing").asBoolean()).isTrue();
        assertThat(profile.path("consents").path("profiling").asBoolean()).isFalse();

        expectError(() -> post("/v1/members", Map.of("firstName", "X", "lastName", "Y",
                "email", "x.y@example.org", "referralCode", "ZZZZZZZZ"), 201), 422, "REFERRAL_CODE_INVALID");
        // Codice di un membro bloccato (Roberto): non valido (SPEC-GAP Q-61).
        String blocked = get("/v1/members/MBR-000008").path("referralCode").asString();
        expectError(() -> post("/v1/members", Map.of("firstName", "X", "lastName", "Z",
                "email", "x.z@example.org", "referralCode", blocked), 201), 422, "REFERRAL_CODE_INVALID");
    }

    @Test
    void overviewCountsLinksAndTopReferrers() {
        JsonNode o = get("/v1/referral/overview");
        assertThat(o.path("invited").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(o.path("qualifyingActionType").asString()).isEqualTo("purchase.completed");
        assertThat(o.path("topReferrers").size()).isGreaterThanOrEqualTo(1);
        assertThat(o.path("links").size()).isEqualTo(o.path("invited").asInt());
    }

    /** F-MBR-07: Anna ha il profilo incompleto (manca la città); al salvataggio dell'ultimo campo un solo fatto. */
    @Test
    void completingProfileEmitsFactOnce() {
        JsonNode before = get("/v1/portal/members/MBR-000001");
        assertThat(before.path("completeness").path("completed").asBoolean()).isFalse();
        assertThat(before.path("completeness").path("missingFields").get(0).asString()).isEqualTo("city");
        assertThat(get("/v1/portal/members/MBR-000002").path("completeness").path("completed").asBoolean()).isTrue();

        JsonNode after = patch("/v1/portal/members/MBR-000001",
                Map.of("version", before.path("version").asLong(), "city", "Parma"));
        assertThat(after.path("completeness").path("completed").asBoolean()).isTrue();
        patch("/v1/portal/members/MBR-000001",
                Map.of("version", after.path("version").asLong(), "consents", Map.of("profiling", true)));

        try (KafkaConsumer<String, String> consumer = consumer("profile-check")) {
            consumer.subscribe(List.of(FACTS));
            List<JsonNode> all = collect(consumer, r -> r.key().equals("MBR-000001") && readJson(r.value())
                    .path("type").asString().equals("io.loyaltyhub.fact.member.profile.completed"), 2);
            assertThat(all).as("member.profile.completed una sola volta").hasSize(1);
        }
        assertThat(get("/v1/portal/members/MBR-000001").path("consents").path("profiling").asBoolean()).isTrue();
    }

    // ---------- helper ----------

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

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private String publishAction(String shortType, String memberId, Map<String, Object> data) {
        String id = "test-" + System.nanoTime();
        Map<String, Object> event = Map.of(
                "specversion", "1.0", "id", id,
                "source", "urn:loyaltyhub:source:ecommerce", "type", "io.loyaltyhub.action." + shortType,
                "subject", "member:" + memberId, "time", Instant.now().toString(),
                "lhcorrelationid", id, "lhhop", 0, "data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(ACTIONS, memberId, mapper.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return id;
    }

    /** Raccoglie i record che combaciano finché ne arrivano {@code max} o scadono 10 s dall'ultimo trovato. */
    private List<JsonNode> collect(KafkaConsumer<String, String> consumer,
                                   Predicate<ConsumerRecord<String, String>> match, int max) {
        List<JsonNode> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && out.size() < max) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                if (match.test(r)) {
                    out.add(readJson(r.value()));
                    deadline = System.currentTimeMillis() + 6_000;
                }
            }
        }
        return out;
    }

    private void expectError(Runnable call, int status, String code) {
        try {
            call.run();
            fail("atteso " + status);
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(status);
            assertThat(e.getResponseBodyAs(JsonNode.class).path("code").asString()).isEqualTo(code);
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

    private JsonNode readJson(String value) {
        return mapper.readTree(value);
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
