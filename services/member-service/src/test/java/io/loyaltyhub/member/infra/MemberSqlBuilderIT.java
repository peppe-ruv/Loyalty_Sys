package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberStatus;
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
 * Elenco e conteggio dei membri col builder SQL comune (regola 19, ADR-042, docs/18 §3.10 punto 4): filtri
 * facoltativi, segmento per codice o id sempre legato, {@code q} letterale ({@code %}, {@code _}, {@code \} non sono
 * caratteri jolly) e tentativi di iniezione senza effetto. Dati propri, non i seed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberSqlBuilderIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String INJECTION = "x%' OR 1=1 --";

    @Autowired
    private MemberRepository repo;

    @Autowired
    private SegmentRepository segmentRepo;

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
        // Via i seed del profilo demo: il test lavora su tre membri e un segmento propri.
        jdbc.sql("DELETE FROM segment_member").update();
        jdbc.sql("DELETE FROM member_projection").update();
        jdbc.sql("DELETE FROM member_stats").update();
        jdbc.sql("DELETE FROM member_activity_day").update();
        repo.deleteAll();
        segmentRepo.deleteAll();

        jdbc.sql("""
                INSERT INTO member (id, first_name, last_name, nickname, email, status, version) VALUES
                  ('MBR-01', 'Alice', 'Smith', 'Ally', 'alice@test.com', 'ACTIVE', 0),
                  ('MBR-02', 'Bob', 'Jones', 'Bobby', 'bob_100%_real@test.com', 'INACTIVE', 0),
                  ('MBR-03', 'Eve', 'Brown', 'Ev\\ie', 'eve@test.com', 'ACTIVE', 0)
                """).update();
        jdbc.sql("""
                INSERT INTO member_projection (member_id, tier_code) VALUES ('MBR-01', 'GOLD'), ('MBR-02', 'BASE')
                """).update();
        jdbc.sql("""
                INSERT INTO segment (id, code, name, type, status, member_count, version)
                VALUES ('SEG-01', 'GOLDEN', 'Golden members', 'STATIC', 'ACTIVE', 0, 0)
                """).update();
        jdbc.sql("""
                INSERT INTO segment_member (segment_id, member_id) VALUES ('SEG-01', 'MBR-01'), ('SEG-01', 'MBR-03')
                """).update();
    }

    @Test
    void searchAppliesOptionalFilters() {
        assertThat(ids(repo.search(null, null, null, null, 10, 0))).containsExactly("MBR-01", "MBR-02", "MBR-03");
        assertThat(ids(repo.search("  ", null, null, "  ", 10, 0))).as("testi vuoti = nessun filtro")
                .containsExactly("MBR-01", "MBR-02", "MBR-03");
        assertThat(ids(repo.search(null, MemberStatus.ACTIVE, null, null, 10, 0))).containsExactly("MBR-01", "MBR-03");
        assertThat(ids(repo.search(null, null, " gold ", null, 10, 0))).containsExactly("MBR-01");
        assertThat(ids(repo.search(" ALI ", null, null, null, 10, 0))).as("ILIKE su testo ripulito")
                .containsExactly("MBR-01");
        assertThat(ids(repo.search("alice", MemberStatus.ACTIVE, "GOLD", "GOLDEN", 10, 0))).containsExactly("MBR-01");
        assertThat(ids(repo.search("bob", MemberStatus.ACTIVE, null, null, 10, 0))).isEmpty();
    }

    @Test
    void searchFiltersBySegmentCodeOrId() {
        assertThat(ids(repo.search(null, null, null, "GOLDEN", 10, 0))).containsExactly("MBR-01", "MBR-03");
        assertThat(ids(repo.search(null, null, null, " SEG-01 ", 10, 0))).containsExactly("MBR-01", "MBR-03");
        assertThat(ids(repo.search(null, null, null, "SEG-99", 10, 0))).isEmpty();
        assertThat(ids(repo.search("eve", null, null, "GOLDEN", 10, 0))).containsExactly("MBR-03");
    }

    @Test
    void searchTreatsLikeWildcardsAsLiterals() {
        assertThat(ids(repo.search("100%_real", null, null, null, 10, 0))).containsExactly("MBR-02");
        assertThat(ids(repo.search("%", null, null, null, 10, 0))).containsExactly("MBR-02");
        assertThat(ids(repo.search("_", null, null, null, 10, 0))).containsExactly("MBR-02");
        assertThat(ids(repo.search("\\", null, null, null, 10, 0))).containsExactly("MBR-03");
        assertThat(ids(repo.search("1_0", null, null, null, 10, 0))).as("_ non vale un carattere qualsiasi").isEmpty();
    }

    @Test
    void searchPaginatesInIdOrder() {
        assertThat(ids(repo.search(null, null, null, null, 2, 0))).containsExactly("MBR-01", "MBR-02");
        assertThat(ids(repo.search(null, null, null, null, 2, 2))).containsExactly("MBR-03");
        assertThat(ids(repo.search(null, null, null, "GOLDEN", 1, 1))).containsExactly("MBR-03");
    }

    @Test
    void countMatchesSearchFilters() {
        assertThat(repo.count(null, null, null, null)).isEqualTo(3);
        assertThat(repo.count(null, MemberStatus.ACTIVE, null, null)).isEqualTo(2);
        assertThat(repo.count(null, null, "GOLD", null)).isEqualTo(1);
        assertThat(repo.count(null, null, null, "GOLDEN")).isEqualTo(2);
        assertThat(repo.count(null, null, null, "SEG-01")).isEqualTo(2);
        assertThat(repo.count("%", null, null, null)).isEqualTo(1);
        assertThat(repo.count("alice", MemberStatus.ACTIVE, "GOLD", "GOLDEN")).isEqualTo(1);
    }

    @Test
    void injectionAttemptsFindNothingAndChangeNothing() {
        assertThat(repo.search(INJECTION, null, null, null, 10, 0)).isEmpty();
        assertThat(repo.search(null, null, INJECTION, null, 10, 0)).isEmpty();
        assertThat(repo.search(null, null, null, INJECTION, 10, 0)).isEmpty();
        assertThat(repo.count(INJECTION, null, INJECTION, INJECTION)).isZero();

        assertThat(jdbc.sql("SELECT count(*) FROM member").query(Long.class).single()).isEqualTo(3);
    }

    private static List<String> ids(List<Member> members) {
        return members.stream().map(Member::id).toList();
    }
}
