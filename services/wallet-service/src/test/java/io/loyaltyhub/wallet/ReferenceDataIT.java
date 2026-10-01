package io.loyaltyhub.wallet;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dati di riferimento del wallet da sola migrazione, senza il profilo {@code demo} (Q-629, opzione B, ADR-049, F-WAL-01,
 * F-TIER-01): dopo {@code V3__reference_data.sql} esistono le valute PTS e STS e la scala dei livelli di base, uguali a
 * {@code seed/}, senza nessun seeder, nessun membro e nessuna edizione. Il contesto non attiva profili: nessun
 * {@code WalletSeeder} né {@code /v1/demo/**}. La convivenza con il seeder e il reset della demo è in {@link DemoResetIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReferenceDataIT {

    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=wallet");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    @Order(1)
    void referenceRowsComeFromTheMigrationAndMatchTheSeed() {
        assertThat(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo")))
                .as("il test non deve girare col profilo demo").isFalse();
        assertThat(context.getBeanNamesForType(io.loyaltyhub.wallet.demo.WalletSeeder.class))
                .as("nessun seeder fuori dal profilo demo").isEmpty();

        ReferenceDataSeedAssertions.assertMatchesSeed(jdbc);
    }

    @Test
    @Order(2)
    void nothingElseIsSeeded() {
        // Q-629: solo valute e livelli di sistema. Edizioni, membri, saldi e movimenti non sono dati di riferimento.
        for (String table : new String[]{"edition", "wallet", "member_tier", "ledger_entry", "points_lot", "tier_history"}) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single())
                    .as("righe in wallet.%s senza profilo demo", table).isZero();
        }
    }

    @Test
    @Order(3)
    void theApiServesTheReferenceRows() {
        JsonNode currencies = client().get().uri("/v1/currencies").header("X-LH-Actor", "ADMIN:marta")
                .retrieve().body(JsonNode.class);
        assertThat(currencies).hasSize(2);
        assertThat(currencies.findValuesAsString("code")).containsExactlyInAnyOrder("PTS", "STS");

        JsonNode tiers = client().get().uri("/v1/tiers").header("X-LH-Actor", "ADMIN:marta")
                .retrieve().body(JsonNode.class);
        assertThat(tiers.findValuesAsString("code")).containsExactly("BASE", "SILVER", "GOLD", "PLATINUM");
    }

    @Test
    @Order(4)
    void migrationIsIdempotentAndNeverOverwritesOperatorChanges() throws Exception {
        // L'operatore (ADMIN) modifica una riga esistente: la PUT agisce proprio sulle righe di riferimento.
        client().put().uri("/v1/currencies/PTS").header("X-LH-Actor", "ADMIN:marta")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"expiryPolicy\":{\"type\":\"ROLLING_MONTHS\",\"months\":24}}")
                .retrieve().toBodilessEntity();
        jdbc.sql("UPDATE tier SET threshold_sts = 1500 WHERE code = 'SILVER'").update();

        // Riesecuzione dello script (per esempio una ripetizione dopo un ripristino): nessun errore, nessuna sovrascrittura.
        String script = new ClassPathResource("db/migration/wallet/V3__reference_data.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(script);
            st.execute(script);
        }

        JsonNode policy = mapper.readTree(jdbc.sql("SELECT expiry_policy::text FROM currency WHERE code = 'PTS'")
                .query(String.class).single());
        assertThat(policy.path("months").asInt()).isEqualTo(24);
        assertThat(jdbc.sql("SELECT threshold_sts FROM tier WHERE code = 'SILVER'").query(Long.class).single())
                .isEqualTo(1500L);
        assertThat(jdbc.sql("SELECT count(*) FROM currency").query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM tier").query(Long.class).single()).isEqualTo(4L);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
