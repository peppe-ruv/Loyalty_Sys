package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.Segment;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
class SegmentSqlBuilderIT {

    private static final EmbeddedPostgres PG = startPg();

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

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM segment_member").update();
        repo.deleteAll();

        // Insert dummy data
        jdbc.sql("INSERT INTO segment (id, code, name, type, status, member_count, version) VALUES ('SEG-01', 'CODE-A', 'First Segment', 'DYNAMIC', 'ACTIVE', 0, 0)").update();
        jdbc.sql("INSERT INTO segment (id, code, name, type, status, member_count, version) VALUES ('SEG-02', 'CODE-B', 'Second % Segment', 'STATIC', 'ARCHIVED', 0, 0)").update();
        jdbc.sql("INSERT INTO segment (id, code, name, type, status, member_count, version) VALUES ('SEG-03', 'CODE-C', 'Third Seg', 'DYNAMIC', 'ACTIVE', 0, 0)").update();
    }

    @Test
    void listFiltersWithSqlWhere() {
        List<Segment> all = repo.list(null, null, null);
        assertThat(all).hasSize(3);

        List<Segment> active = repo.list(null, null, " ACTIVE ");
        assertThat(active).hasSize(2).extracting(Segment::code).containsExactly("CODE-A", "CODE-C");

        List<Segment> dynamic = repo.list(null, "DYNAMIC", null);
        assertThat(dynamic).hasSize(2).extracting(Segment::code).containsExactly("CODE-A", "CODE-C");

        List<Segment> matchFirst = repo.list("first", null, null);
        assertThat(matchFirst).hasSize(1).extracting(Segment::code).containsExactly("CODE-A");

        // Literal match of '%' test
        List<Segment> matchPercent = repo.list("%", null, null);
        assertThat(matchPercent).hasSize(1).extracting(Segment::code).containsExactly("CODE-B");
    }

    @Test
    void sqlInjectionAttemptOnList() {
        String maliciousQuery = "x' OR '1'='1";
        List<Segment> result = repo.list(maliciousQuery, null, null);
        assertThat(result).isEmpty();

        List<Segment> resultType = repo.list(null, maliciousQuery, null);
        assertThat(resultType).isEmpty();

        assertThat(repo.list(null, null, null)).hasSize(3);
    }
}
