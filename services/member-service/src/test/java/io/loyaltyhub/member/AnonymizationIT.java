package io.loyaltyhub.member;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anonimizzazione nel member-service (F-MBR-05, docs/servizi/member-service.md §3, M7.5): solo ADMIN, conferma con
 * l'id digitato, riga ripulita, fatti {@code member.status.changed} (→ ANONYMIZED) e {@code member.updated} senza dati
 * personali, audit senza dati personali, stato irreversibile. Contesto separato da {@link MemberServiceIT} (che conta i
 * membri del seed). Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnonymizationIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";

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
    void anonymizeRedactsTheMemberAndPublishesCleanFacts() {
        JsonNode created = send("POST", "/v1/members", "CARE:paolo.care", Map.of(
                "firstName", "Ottone", "lastName", "Brambillaschi", "email", "ottone.brambillaschi@example.org",
                "phone", "+39 333 7654321", "city", "Cremona", "channel", "STORE"), 201);
        String id = created.path("id").asString();
        send("PATCH", "/v1/members/" + id, "CARE:paolo.care",
                Map.of("birthDate", "1980-02-03", "attributes", Map.of("householdSize", 3), "labels", List.of("vip")), 200);

        // Solo ADMIN (docs/08 §2 member.anonymize) e solo con l'id digitato.
        assertThat(send("POST", "/v1/members/" + id + "/anonymize", "CARE:paolo.care", Map.of("confirm", id), 403)
                .path("code").asString()).isNotBlank();
        assertThat(send("POST", "/v1/members/" + id + "/anonymize", ADMIN, Map.of("confirm", "MBR-000001"), 422)
                .path("code").asString()).isEqualTo("CONFIRM_MISMATCH");

        try (KafkaConsumer<String, String> consumer = consumer("anon-check")) {
            consumer.subscribe(List.of("lh.facts.v1", "lh.audit.v1"));
            JsonNode anonymized = send("POST", "/v1/members/" + id + "/anonymize", ADMIN, Map.of("confirm", id), 200);
            assertThat(anonymized.path("status").asString()).isEqualTo("ANONYMIZED");
            assertThat(anonymized.path("nickname").asString()).isEqualTo("Membro anonimo");
            for (String field : List.of("firstName", "lastName", "email", "phone", "birthDate", "city", "externalId")) {
                assertThat(anonymized.hasNonNull(field)).as(field + " cancellato").isFalse();
            }
            assertThat(anonymized.path("attributes").size()).as("attributi cancellati").isZero();
            assertThat(anonymized.path("labels").toString()).contains("vip");

            List<JsonNode> facts = collect(consumer, id, 3);
            List<String> types = facts.stream().map(f -> f.path("type").asString()).toList();
            assertThat(types).as("stato poi snapshot, più l'audit")
                    .containsSubsequence("io.loyaltyhub.fact.member.status.changed", "io.loyaltyhub.fact.member.updated")
                    .contains("io.loyaltyhub.audit.entry");
            JsonNode status = facts.get(types.indexOf("io.loyaltyhub.fact.member.status.changed")).path("data");
            assertThat(status.path("previousStatus").asString()).isEqualTo("ACTIVE");
            assertThat(status.path("newStatus").asString()).isEqualTo("ANONYMIZED");
            JsonNode snapshot = facts.get(types.indexOf("io.loyaltyhub.fact.member.updated")).path("data");
            assertThat(snapshot.path("status").asString()).isEqualTo("ANONYMIZED");
            assertThat(snapshot.path("nickname").asString()).isEqualTo("Membro anonimo");
            for (JsonNode f : facts) {
                assertThat(f.toString()).as("nessun dato personale nei fatti e nell'audit di anonimizzazione")
                        .doesNotContainIgnoringCase("Ottone").doesNotContainIgnoringCase("Brambillaschi")
                        .doesNotContain("7654321").doesNotContain("Cremona").doesNotContain("1980-02-03");
            }
        }

        // Irreversibile: niente seconda anonimizzazione, niente modifiche, niente cambio stato.
        assertThat(send("POST", "/v1/members/" + id + "/anonymize", ADMIN, Map.of("confirm", id), 409)
                .path("code").asString()).isEqualTo("MEMBER_ANONYMIZED");
        assertThat(send("PATCH", "/v1/members/" + id, ADMIN, Map.of("firstName", "Ottone"), 409)
                .path("code").asString()).isEqualTo("MEMBER_ANONYMIZED");
        assertThat(send("POST", "/v1/members/" + id + "/status", ADMIN, Map.of("status", "ACTIVE"), 409)
                .path("code").asString()).isEqualTo("MEMBER_ANONYMIZED");

        // Le ricerche per nome/e-mail non lo trovano più; per id sì, con il segnaposto.
        assertThat(send("GET", "/v1/members?q=Ottone", ADMIN, null, 200).path("page").path("totalItems").asInt()).isZero();
        assertThat(send("GET", "/v1/members?q=brambillaschi@example", ADMIN, null, 200).path("page").path("totalItems").asInt())
                .isZero();
        JsonNode byId = send("GET", "/v1/members?q=" + id, ADMIN, null, 200).path("items").get(0);
        assertThat(byId.path("nickname").asString()).isEqualTo("Membro anonimo");
        assertThat(send("GET", "/v1/portal/members/" + id, null, null, 200).toString())
                .doesNotContain("Ottone").doesNotContain("Cremona");
        // L'e-mail è di nuovo libera (non è più di nessuno).
        send("POST", "/v1/members", "CARE:paolo.care", Map.of(
                "firstName", "Altra", "lastName", "Persona", "email", "ottone.brambillaschi@example.org"), 201);
    }

    @Test
    void seededAnonymizedMemberFollowsTheSameRule() {
        JsonNode m = send("GET", "/v1/members/MBR-000012", null, null, 200);
        assertThat(m.path("status").asString()).isEqualTo("ANONYMIZED");
        assertThat(m.path("nickname").asString()).isEqualTo("Membro anonimo");
        assertThat(m.hasNonNull("firstName")).isFalse();
        assertThat(m.hasNonNull("email")).isFalse();
        assertThat(send("POST", "/v1/members/MBR-000012/anonymize", ADMIN, Map.of("confirm", "MBR-000012"), 409)
                .path("code").asString()).isEqualTo("MEMBER_ANONYMIZED");
    }

    // ---------- helper ----------

    private List<JsonNode> collect(KafkaConsumer<String, String> consumer, String memberId, int atLeast) {
        List<JsonNode> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && out.size() < atLeast) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                JsonNode e = mapper.readTree(r.value());
                String type = e.path("type").asString();
                boolean mine = memberId.equals(r.key()) || e.path("data").path("entityId").asString().equals(memberId);
                boolean relevant = type.endsWith("member.status.changed")
                        || (type.endsWith("member.updated") && e.path("data").path("status").asString().equals("ANONYMIZED"))
                        || (type.equals("io.loyaltyhub.audit.entry") && e.path("data").path("action").asString().equals("TRANSITION"));
                if (mine && relevant) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
