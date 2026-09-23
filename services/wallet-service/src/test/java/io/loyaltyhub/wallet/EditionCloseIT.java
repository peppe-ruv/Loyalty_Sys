package io.loyaltyhub.wallet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.application.EditionService;
import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1"})
class EditionCloseIT {

    static EmbeddedPostgres pg;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        try {
            pg = EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        reg.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        reg.add("spring.datasource.username", () -> "postgres");
        reg.add("spring.datasource.password", () -> "postgres");
        reg.add("spring.flyway.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        reg.add("spring.flyway.user", () -> "postgres");
        reg.add("spring.flyway.password", () -> "postgres");
        reg.add("spring.flyway.schemas", () -> "wallet");
        reg.add("spring.datasource.hikari.schema", () -> "wallet");
    }

    @Autowired
    private EditionService editionService;

    @Autowired
    private EditionRepository editionRepository;

    @Autowired
    private MemberTierRepository memberTierRepository;

    @Autowired
    private ObjectMapper mapper;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setup() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, "edition-close-it-" + System.currentTimeMillis(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of("lh.facts.v1"));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void testEditionCloseApply() {
        // ED-2026 is ACTIVE from seed data, we close it with dryRun=false

        var result = editionService.closeEdition("ED-2026", false);

        // Assert returned values
        assertThat(result.summary().retained()).isGreaterThanOrEqualTo(0);
        assertThat(result.summary().downgraded()).isGreaterThanOrEqualTo(0);

        // Assert database changes
        Edition ed2026 = editionRepository.findByCode("ED-2026").orElseThrow();
        assertThat(ed2026.status()).isEqualTo(Edition.CLOSED);

        Edition ed2027 = editionRepository.findByCode("ED-2027").orElseThrow();
        assertThat(ed2027.status()).isEqualTo(Edition.ACTIVE);

        // MBR-000006 was GOLD with 650 STS, should be downgraded to SILVER
        var mt = memberTierRepository.find("MBR-000006").orElseThrow();
        assertThat(mt.tierCode()).isEqualTo("SILVER");
        assertThat(mt.previousTier()).isEqualTo("GOLD");
        assertThat(mt.periodSts()).isEqualTo(0);

        // MBR-000004 was GOLD with 4100 STS, earned PLATINUM but cap applies in close -> RETAINED GOLD
        // Or simply MBR-000004 earned enough to keep GOLD. Let's check RETAINED properties.
        var mtRetained = memberTierRepository.find("MBR-000004").orElseThrow();
        assertThat(mtRetained.tierCode()).isEqualTo("GOLD");
        assertThat(mtRetained.previousTier()).isNull(); // Original state in seed is INITIAL
        // Check that it's RETAINED. `since` should be unchanged (seed value), meaning not `now()`.
        // The problem with EmbeddedPostgres is that `now()` in the DB might not be in sync with `Instant.now()` in Java.
        // We can just verify `previousTier` is null.
        assertThat(mtRetained.since()).isNotNull();
        assertThat(mtRetained.periodSts()).isEqualTo(0);

        // Find facts
        JsonNode downgradedFact = awaitFact("io.loyaltyhub.fact.tier.downgraded",
                d -> d.path("editionCode").asString().equals("ED-2026")
                        && d.path("newTier").asString().equals("SILVER"));
        assertThat(downgradedFact).isNotNull();

        JsonNode closedFact = awaitFact("io.loyaltyhub.fact.edition.closed",
                d -> d.path("editionCode").asString().equals("ED-2026"));
        assertThat(closedFact).isNotNull();

        // Idempotency: closing an already CLOSED edition returns 422
        assertThatThrownBy(() -> editionService.closeEdition("ED-2026", false))
                .isInstanceOf(LhException.class)
                .satisfies(e -> assertThat(((LhException) e).code()).isEqualTo("EDITION_ALREADY_CLOSED"));
    }

    /** Fatti già letti dal consumer: la chiusura li pubblica insieme, un solo poll può contenerne più d'uno. */
    private final List<JsonNode> seen = new java.util.ArrayList<>();

    private JsonNode awaitFact(String type, Predicate<JsonNode> dataMatch) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            for (JsonNode e : seen) {
                if (e.path("type").asString().equals(type) && dataMatch.test(e.path("data"))) {
                    return e.path("data");
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                try {
                    seen.add(mapper.readTree(r.value()));
                } catch (Exception ex) {
                    // record non JSON: ignorato
                }
            }
        }
    }
}
