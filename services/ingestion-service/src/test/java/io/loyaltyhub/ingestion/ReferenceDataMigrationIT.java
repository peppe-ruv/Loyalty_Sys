package io.loyaltyhub.ingestion;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dati di riferimento di sistema da migrazione in ogni profilo (Q-629 opzione B, F-ING-06, F-ING-08, ADR-049).
 * <p>
 * Il contesto parte <b>senza</b> il profilo {@code demo} (nessun {@code DemoSeeder}): quello che trova nel database lo
 * ha messo soltanto {@code V6__ingestion_reference_data.sql}. Il test confronta le righe con {@code seed/} (stessi
 * codici e attributi, stessi JSON Schema) e con {@code contracts/events/action/} (precedenza 2), e fallisce se la
 * migrazione diverge da una delle due fonti. Verifica anche che rieseguirla sia innocua: {@code DO NOTHING} non
 * sovrascrive le modifiche di un operatore e ripristina una riga mancante.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1", "lh.audit.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceDataMigrationIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MIGRATION = "db/migration/ingestion/V6__ingestion_reference_data.sql";

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

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
    void migrationsAloneCreateTheSystemActionTypesFromTheSeed() throws Exception {
        JsonNode seed = readSeed("event-types.json");
        List<String> seedCodes = new ArrayList<>();
        for (JsonNode t : seed) {
            if ("SYSTEM".equals(t.path("origin").asString("SYSTEM"))) {
                seedCodes.add(t.path("code").asString());
            }
        }
        assertThat(seedCodes).hasSize(19);
        // Nessun DemoSeeder in questo contesto: nessun tipo oltre ai SYSTEM della migrazione, nessuna fonte, nessun membro.
        assertThat(jdbc.sql("SELECT code FROM event_type ORDER BY code").query(String.class).list())
                .containsExactlyInAnyOrderElementsOf(seedCodes);
        assertThat(count("source")).as("le fonti non sono dati di riferimento (le crea lo script di programma)").isZero();
        assertThat(count("member_index")).isZero();
        assertThat(count("scenario")).isZero();

        for (JsonNode t : seed) {
            String code = t.path("code").asString();
            var row = jdbc.sql("""
                            SELECT name, description, origin, category, data_schema::text AS data_schema,
                                   sample_data::text AS sample_data, enabled, icon
                            FROM event_type WHERE code = ?
                            """)
                    .param(code)
                    .query((rs, n) -> new String[]{rs.getString("name"), rs.getString("description"),
                            rs.getString("origin"), rs.getString("category"), rs.getString("data_schema"),
                            rs.getString("sample_data"), String.valueOf(rs.getBoolean("enabled")), rs.getString("icon")})
                    .single();
            assertThat(row[0]).as("%s.name", code).isEqualTo(t.path("name").asString());
            assertThat(row[1]).as("%s.description", code).isEqualTo(t.path("description").asString(null));
            assertThat(row[2]).as("%s.origin", code).isEqualTo("SYSTEM");
            assertThat(row[3]).as("%s.category", code).isEqualTo(t.path("category").asString(null));
            assertSameJson(code + ".dataSchema", mapper.readTree(row[4]), t.get("dataSchema"));
            assertSameJson(code + ".sampleData", mapper.readTree(row[5]), t.get("sampleData"));
            assertThat(row[6]).as("%s.enabled", code).isEqualTo(String.valueOf(t.path("enabled").asBoolean(true)));
            assertThat(row[7]).as("%s.icon", code).isEqualTo(t.path("icon").asString(null));
        }
    }

    @Test
    void systemSchemasMatchTheActionContracts() throws Exception {
        Path dir = Path.of("").toAbsolutePath().resolve("../../contracts/events/action").normalize();
        assertThat(dir).as("contracts/events/action raggiungibile dal modulo").isDirectory();
        int checked = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".schema.json")).toList()) {
                String code = f.getFileName().toString().replaceFirst("\\.schema\\.json$", "");
                JsonNode contract = mapper.readTree(Files.readString(f));
                ((tools.jackson.databind.node.ObjectNode) contract).remove(List.of("$id", "title"));
                String stored = jdbc.sql("SELECT data_schema::text FROM event_type WHERE code = ?").param(code)
                        .query(String.class).optional().orElse(null);
                assertThat(stored).as("tipo azione %s per il contratto %s", code, f.getFileName()).isNotNull();
                assertSameJson("dataSchema di " + code, mapper.readTree(stored), contract);
                checked++;
            }
        }
        assertThat(checked).as("un contratto per ogni tipo SYSTEM").isEqualTo(19);
    }

    @Test
    void migrationsAloneCreateTheBridgeMappingsFromTheSeed() throws Exception {
        JsonNode seed = readSeed("internal-mappings.json");
        assertThat(seed).hasSize(9);
        assertThat(count("internal_mapping")).isEqualTo(9);
        for (JsonNode m : seed) {
            String actionType = jdbc.sql("SELECT action_type FROM internal_mapping WHERE fact_type = ? AND enabled = ?")
                    .params(m.path("factType").asString(), m.path("enabled").asBoolean(true))
                    .query(String.class).single();
            assertThat(actionType).isEqualTo(m.path("actionType").asString());
            // Ogni azione del ponte è un tipo azione che esiste (docs/04: nessuna entità senza lettore).
            assertThat(jdbc.sql("SELECT count(*) FROM event_type WHERE code = ?").param(actionType)
                    .query(Long.class).single()).isEqualTo(1L);
        }
    }

    @Test
    void rerunningTheMigrationKeepsOperatorEditsAndRestoresMissingRows() throws Exception {
        try {
            jdbc.sql("UPDATE event_type SET name = 'Rinominato da operatore', enabled = false WHERE code = 'purchase.completed'")
                    .update();
            jdbc.sql("UPDATE internal_mapping SET enabled = false WHERE fact_type = 'fact.tier.upgraded'").update();
            jdbc.sql("DELETE FROM event_type WHERE code = 'newsletter.subscribed'").update();
            jdbc.sql("DELETE FROM internal_mapping WHERE fact_type = 'fact.badge.awarded'").update();

            runMigrationScript();
            runMigrationScript();

            assertThat(jdbc.sql("SELECT name FROM event_type WHERE code = 'purchase.completed'").query(String.class)
                    .single()).isEqualTo("Rinominato da operatore");
            assertThat(jdbc.sql("SELECT enabled FROM event_type WHERE code = 'purchase.completed'").query(Boolean.class)
                    .single()).isFalse();
            assertThat(jdbc.sql("SELECT enabled FROM internal_mapping WHERE fact_type = 'fact.tier.upgraded'")
                    .query(Boolean.class).single()).isFalse();
            assertThat(count("event_type")).isEqualTo(19);
            assertThat(count("internal_mapping")).isEqualTo(9);
            assertThat(jdbc.sql("SELECT origin FROM event_type WHERE code = 'newsletter.subscribed'")
                    .query(String.class).single()).isEqualTo("SYSTEM");
        } finally {
            // Lo stato di riferimento torna quello della migrazione: gli altri test non dipendono dall'ordine.
            jdbc.sql("DELETE FROM internal_mapping").update();
            jdbc.sql("DELETE FROM event_type").update();
            runMigrationScript();
        }
    }

    private static void assertSameJson(String what, JsonNode actual, JsonNode expected) {
        assertThat(sameJson(actual, expected)).as("%s: atteso %s, trovato %s", what, expected, actual).isTrue();
    }

    /** Uguaglianza semantica: chiavi degli oggetti senza ordine, numeri per valore (25 e 25.0 coincidono). */
    private static boolean sameJson(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (String name : a.propertyNames()) {
                if (!b.has(name) || !sameJson(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameJson(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private void runMigrationScript() throws Exception {
        try (var c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(MIGRATION), "UTF-8"));
        }
    }

    private long count(String table) {
        // Tabella da elenco fisso del test, mai input: il testo SQL resta costante per ogni ramo.
        return switch (table) {
            case "source" -> jdbc.sql("SELECT count(*) FROM source").query(Long.class).single();
            case "member_index" -> jdbc.sql("SELECT count(*) FROM member_index").query(Long.class).single();
            case "scenario" -> jdbc.sql("SELECT count(*) FROM scenario").query(Long.class).single();
            case "event_type" -> jdbc.sql("SELECT count(*) FROM event_type").query(Long.class).single();
            case "internal_mapping" -> jdbc.sql("SELECT count(*) FROM internal_mapping").query(Long.class).single();
            default -> throw new IllegalArgumentException(table);
        };
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
