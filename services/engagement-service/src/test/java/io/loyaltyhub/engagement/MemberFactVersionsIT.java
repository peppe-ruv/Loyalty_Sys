package io.loyaltyhub.engagement;

import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8.4e (ADR-032, docs/18 §3.4, Q-346): engagement legge {@code member.registered/updated} sia {@code :1} sia
 * {@code :2}. Un {@code member.registered:1} con nome seguito da un {@code member.updated:2} (senza nome, con
 * {@code locale}) lascia il nome nello snapshot; il fatto successivo è elaborato e il messaggio usa ancora il nome.
 * Un membro visto solo in {@code :2} riceve il benvenuto con il segnaposto reso come oggi per un valore assente
 * (stringa vuota). EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberFactVersionsIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String FACTS = "lh.facts.v1";
    private static final String FACT = "io.loyaltyhub.fact.";
    private static final String SCHEMA = "urn:loyaltyhub:schema:fact.";
    private static final String EMAIL_HASH = "0123456789abcdef".repeat(4);

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonSchemaValidator validator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void registeredV1ThenUpdatedV2KeepsTheFirstNameAndProcessingGoesOn() throws Exception {
        String member = freshMemberId();
        Instant registeredAt = Instant.parse("2026-01-15T10:00:00Z");

        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("memberId", member);
        v1.put("firstName", "Ottavia");
        v1.put("lastName", "Doppialettura");
        v1.put("email", "ottavia@example.org");
        v1.put("status", "ACTIVE");
        v1.put("channel", "WEB");
        v1.put("registeredAt", registeredAt.toString());
        String registered = publish("member.registered", 1, member, v1);
        awaitProcessed(registered);
        assertThat(snapshot(member, "first_name")).isEqualTo("Ottavia");
        JsonNode welcome = awaitMessage(member, m -> registered.equals(m.path("sourceEventId").asString()));
        assertThat(welcome.path("title").asString()).isEqualTo("Benvenuto nel Club Aurora, Ottavia");

        Map<String, Object> v2 = v2Data(member, "SUSPENDED");
        assertValidV2("member.updated", v2);
        String updated = publish("member.updated", 2, member, v2);
        awaitProcessed(updated);
        assertThat(snapshot(member, "first_name")).as("il :2 non porta il nome: resta quello del :1").isEqualTo("Ottavia");
        assertThat(snapshot(member, "status")).isEqualTo("SUSPENDED");
        assertThat(jdbc.sql("SELECT registered_at FROM engagement_member_snapshot WHERE member_id = ?").param(member)
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(registeredAt);

        // Il servizio continua a elaborare: il fatto successivo produce il messaggio con il nome noto.
        String earned = publish("wallet.points.earned", 1, member, Map.of("ledgerEntryId", "LED-" + member,
                "effectId", "EFF-" + member, "campaignCode", "CMP-PURCHASE-BASE", "currency", "PTS", "baseAmount", 40,
                "amount", 40, "balanceAfter", 140, "pending", false));
        JsonNode points = awaitMessage(member, m -> earned.equals(m.path("sourceEventId").asString()));
        assertThat(points.path("title").asString()).isEqualTo("Hai guadagnato 40 punti");
        assertThat(points.path("body").asString()).contains("Ottavia");
    }

    @Test
    void memberSeenOnlyInV2GetsTheWelcomeWithTheMissingValueRenderedAsToday() throws Exception {
        String member = freshMemberId();
        Map<String, Object> v2 = v2Data(member, "ACTIVE");
        assertValidV2("member.registered", v2);
        String registered = publish("member.registered", 2, member, v2);
        awaitProcessed(registered);

        assertThat(snapshot(member, "first_name")).isNull();
        assertThat(snapshot(member, "status")).isEqualTo("ACTIVE");
        JsonNode welcome = awaitMessage(member, m -> registered.equals(m.path("sourceEventId").asString()));
        assertThat(welcome.path("title").asString()).isEqualTo("Benvenuto nel Club Aurora, ").doesNotContain("{{");
    }

    // ---------- helper ----------

    private static Map<String, Object> v2Data(String member, String status) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("memberId", member);
        d.put("emailHash", EMAIL_HASH);
        d.put("status", status);
        d.put("channel", "WEB");
        d.put("locale", "en");
        d.put("birthYear", 1991);
        d.put("province", "MI");
        d.put("labels", java.util.List.of());
        return d;
    }

    /** Il payload {@code :2} di prova rispetta lo schema di contratto (docs/05 §9). */
    private void assertValidV2(String name, Map<String, Object> data) throws Exception {
        String schema = new ClassPathResource("contracts/events/fact/" + name + ".v2.schema.json")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(validator.validate("it-" + name + "-v2", schema, mapper.writeValueAsString(data))).isEmpty();
    }

    private String publish(String shortType, int version, String member, Map<String, Object> data) throws Exception {
        String id = "EVT-M84E-" + UUID.randomUUID();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", "urn:loyaltyhub:service:member");
        e.put("type", FACT + shortType);
        e.put("subject", "member:" + member);
        e.put("time", Instant.now().toString());
        e.put("datacontenttype", "application/json");
        e.put("dataschema", SCHEMA + shortType + ":" + version);
        e.put("lhtenant", "aurora");
        e.put("lhcorrelationid", "CORR-" + id);
        e.put("lhhop", 0);
        e.put("data", data);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>(FACTS, member, mapper.writeValueAsString(e))).get();
        }
        return id;
    }

    private String snapshot(String member, String column) {
        Optional<String> value = jdbc.sql("SELECT " + switch (column) {
                    case "first_name" -> "first_name";
                    case "status" -> "status";
                    default -> throw new IllegalArgumentException(column);
                } + " FROM engagement_member_snapshot WHERE member_id = ?")
                .param(member).query((rs, n) -> Optional.ofNullable(rs.getString(1))).optional().flatMap(v -> v);
        return value.orElse(null);
    }

    private JsonNode awaitMessage(String member, Predicate<JsonNode> match) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode m : get("/v1/messages?size=100&memberId=" + member).path("items")) {
                if (match.test(m)) {
                    return m;
                }
            }
            Thread.sleep(300);
        }
        throw new AssertionError("messaggio non arrivato per " + member);
    }

    private void awaitProcessed(String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            long n = jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(eventId)
                    .query(Long.class).single();
            if (n > 0) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("evento non elaborato: " + eventId);
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private static String freshMemberId() {
        return "MBR-8" + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
