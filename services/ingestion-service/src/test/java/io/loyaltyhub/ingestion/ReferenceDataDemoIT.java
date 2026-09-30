package io.loyaltyhub.ingestion;

import io.loyaltyhub.ingestion.demo.DemoSeeder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Convivenza tra la migrazione dei dati di riferimento ({@code V6}, Q-629) e il seeder della demo: all'avvio col profilo
 * {@code demo} la migrazione ha già inserito i tipi SYSTEM e il ponte e il {@code DemoSeeder} li rimpiazza senza
 * violazioni di chiave; {@code POST /v1/demo/reset} (qui {@code resetToSeed}, ripetuto) cancella e reinserisce gli
 * stessi dati dentro una transazione, quindi dopo ogni reset le righe ci sono ancora e coincidono con {@code seed/}.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1", "lh.audit.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceDataDemoIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DemoSeeder seeder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void referenceDataSurvivesStartupAndRepeatedDemoResets() throws Exception {
        assertMatchesSeed();
        // Un tipo custom creato dopo il seed sparisce col reset (docs/06 §10), i SYSTEM restano.
        jdbc.sql("""
                INSERT INTO event_type (code, name, origin, category, enabled)
                VALUES ('meter.reading.sent', 'Lettura inviata', 'CUSTOM', 'SERVICE', true)
                """).update();
        jdbc.sql("UPDATE internal_mapping SET enabled = false WHERE fact_type = 'fact.tier.upgraded'").update();

        seeder.resetToSeed();
        assertMatchesSeed();
        seeder.resetToSeed();
        assertMatchesSeed();
    }

    private void assertMatchesSeed() throws Exception {
        List<String> systemCodes = new ArrayList<>();
        JsonNode types = readSeed("event-types.json");
        for (JsonNode t : types) {
            systemCodes.add(t.path("code").asString());
        }
        assertThat(jdbc.sql("SELECT code FROM event_type WHERE origin = 'SYSTEM'").query(String.class).list())
                .containsExactlyInAnyOrderElementsOf(systemCodes);
        assertThat(jdbc.sql("SELECT count(*) FROM event_type").query(Long.class).single()).isEqualTo((long) systemCodes.size());
        JsonNode mappings = readSeed("internal-mappings.json");
        assertThat(jdbc.sql("SELECT count(*) FROM internal_mapping").query(Long.class).single())
                .isEqualTo((long) mappings.size());
        for (JsonNode m : mappings) {
            assertThat(jdbc.sql("SELECT action_type FROM internal_mapping WHERE fact_type = ? AND enabled = ?")
                    .params(m.path("factType").asString(), m.path("enabled").asBoolean(true))
                    .query(String.class).single()).isEqualTo(m.path("actionType").asString());
        }
    }

    private JsonNode readSeed(String file) throws Exception {
        try (InputStream in = new ClassPathResource("seed/" + file).getInputStream()) {
            return mapper.readTree(in);
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
