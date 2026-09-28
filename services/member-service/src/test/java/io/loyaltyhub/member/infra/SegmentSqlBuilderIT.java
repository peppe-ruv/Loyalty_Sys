package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.Segment;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elenco dei segmenti col builder SQL comune (regola 19, ADR-042, docs/18 §3.10 punto 4): filtri facoltativi,
 * {@code q} letterale ({@code %}, {@code _}, {@code \} non sono caratteri jolly) e tentativi di iniezione senza
 * effetto. Dati propri, non i seed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SegmentSqlBuilderIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String INJECTION = "x%' OR 1=1 --";

    @Autowired
    private SegmentRepository repo;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=member");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @BeforeEach
    void setUp() {
        // Via i seed del profilo demo: il test lavora su tre segmenti propri.
        jdbc.sql("DELETE FROM segment_member").update();
        repo.deleteAll();

        jdbc.sql("""
                INSERT INTO segment (id, code, name, type, status, member_count, version) VALUES
                  ('SEG-01', 'CODE-A', 'First Segment', 'DYNAMIC', 'ACTIVE', 0, 0),
                  ('SEG-02', 'CODE-B', 'Second % _ Segment', 'STATIC', 'ARCHIVED', 0, 0),
                  ('SEG-03', 'CODE-C', 'Third \\ Seg', 'DYNAMIC', 'ACTIVE', 0, 0)
                """).update();
    }

    @Test
    void listAppliesOptionalFiltersInCodeOrder() {
        assertThat(codes(repo.list(null, null, null))).containsExactly("CODE-A", "CODE-B", "CODE-C");
        assertThat(codes(repo.list(" ", " ", " "))).as("testi vuoti = nessun filtro")
                .containsExactly("CODE-A", "CODE-B", "CODE-C");
        assertThat(codes(repo.list(null, null, " active "))).containsExactly("CODE-A", "CODE-C");
        assertThat(codes(repo.list(null, "DYNAMIC", null))).containsExactly("CODE-A", "CODE-C");
        assertThat(codes(repo.list(" FIRST ", null, null))).containsExactly("CODE-A");
        assertThat(codes(repo.list("code-", "static", "archived"))).containsExactly("CODE-B");
    }

    @Test
    void listTreatsLikeWildcardsAsLiterals() {
        assertThat(codes(repo.list("%", null, null))).containsExactly("CODE-B");
        assertThat(codes(repo.list("_", null, null))).containsExactly("CODE-B");
        assertThat(codes(repo.list("\\", null, null))).containsExactly("CODE-C");
        assertThat(codes(repo.list("CODE_A", null, null))).as("_ non vale un carattere qualsiasi").isEmpty();
    }

    @Test
    void injectionAttemptsFindNothingAndChangeNothing() {
        assertThat(repo.list(INJECTION, null, null)).isEmpty();
        assertThat(repo.list(null, INJECTION, null)).isEmpty();
        assertThat(repo.list(null, null, INJECTION)).isEmpty();

        assertThat(jdbc.sql("SELECT count(*) FROM segment").query(Long.class).single()).isEqualTo(3);
    }

    private static List<String> codes(List<Segment> segments) {
        return segments.stream().map(Segment::code).toList();
    }
}
