package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
class MemberSqlBuilderIT {

    private static final EmbeddedPostgres PG = startPg();

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

    @BeforeEach
    void setUp() {
        // Clear tables
        jdbc.sql("DELETE FROM segment_member").update();
        jdbc.sql("DELETE FROM member_projection").update();
        jdbc.sql("DELETE FROM member_stats").update();
        jdbc.sql("DELETE FROM member_activity_day").update();
        repo.deleteAll();
        segmentRepo.deleteAll();

        // Insert dummy data
        jdbc.sql("INSERT INTO member (id, first_name, last_name, nickname, email, status, version) VALUES ('MBR-01', 'Alice', 'Smith', 'Ally', 'alice@test.com', 'ACTIVE', 0)").update();
        jdbc.sql("INSERT INTO member_projection (member_id, tier_code) VALUES ('MBR-01', 'GOLD') ON CONFLICT DO NOTHING").update();

        jdbc.sql("INSERT INTO member (id, first_name, last_name, nickname, email, status, version) VALUES ('MBR-02', 'Bob', 'Jones', 'Bobby', 'bob_100%_real@test.com', 'INACTIVE', 0)").update();
        jdbc.sql("INSERT INTO member_projection (member_id, tier_code) VALUES ('MBR-02', 'BASE') ON CONFLICT DO NOTHING").update();

        jdbc.sql("INSERT INTO member (id, first_name, last_name, nickname, email, status, version) VALUES ('MBR-03', 'Eve', 'Brown', 'Evie', 'eve@test.com', 'ACTIVE', 0)").update();

        jdbc.sql("INSERT INTO segment (id, code, name, type, status, member_count, version) VALUES ('SEG-01', 'GOLDEN', 'Golden members', 'STATIC', 'ACTIVE', 0, 0)").update();
        jdbc.sql("INSERT INTO segment_member (segment_id, member_id) VALUES ('SEG-01', 'MBR-01')").update();
        jdbc.sql("INSERT INTO segment_member (segment_id, member_id) VALUES ('SEG-01', 'MBR-03')").update();
    }

    @Test
    void searchFiltersWithSqlWhere() {
        List<Member> all = repo.search(null, null, null, null, 10, 0);
        assertThat(all).hasSize(3);

        List<Member> active = repo.search(null, MemberStatus.ACTIVE, null, null, 10, 0);
        assertThat(active).hasSize(2).extracting(Member::id).containsExactly("MBR-01", "MBR-03");

        List<Member> gold = repo.search(null, null, " GOLD ", null, 10, 0);
        assertThat(gold).hasSize(1).extracting(Member::id).containsExactly("MBR-01");

        // Literal match of '%' and '_' test
        List<Member> matchBob = repo.search("100%_real", null, null, null, 10, 0);
        assertThat(matchBob).hasSize(1).extracting(Member::id).containsExactly("MBR-02");

        // Should NOT match everything by accident due to % being escaped
        List<Member> matchOnlyPercent = repo.search("%", null, null, null, 10, 0);
        assertThat(matchOnlyPercent).hasSize(1).extracting(Member::id).containsExactly("MBR-02");

        List<Member> matchOnlyUnderscore = repo.search("_", null, null, null, 10, 0);
        assertThat(matchOnlyUnderscore).hasSize(1).extracting(Member::id).containsExactly("MBR-02");

        List<Member> matchSegment = repo.search(null, null, null, "GOLDEN", 10, 0);
        assertThat(matchSegment).hasSize(2).extracting(Member::id).containsExactly("MBR-01", "MBR-03");

        List<Member> matchAllCombos = repo.search("alice", MemberStatus.ACTIVE, "GOLD", "GOLDEN", 10, 0);
        assertThat(matchAllCombos).hasSize(1).extracting(Member::id).containsExactly("MBR-01");
    }

    @Test
    void paginationWorksCorrectly() {
        List<Member> page1 = repo.search(null, null, null, null, 2, 0);
        assertThat(page1).hasSize(2).extracting(Member::id).containsExactly("MBR-01", "MBR-02");

        List<Member> page2 = repo.search(null, null, null, null, 2, 2);
        assertThat(page2).hasSize(1).extracting(Member::id).containsExactly("MBR-03");
    }

    @Test
    void countFiltersWithSqlWhere() {
        long all = repo.count(null, null, null, null);
        assertThat(all).isEqualTo(3);

        long active = repo.count(null, MemberStatus.ACTIVE, null, null);
        assertThat(active).isEqualTo(2);

        long gold = repo.count(null, null, "GOLD", null);
        assertThat(gold).isEqualTo(1);

        long segmentCount = repo.count("alice", MemberStatus.ACTIVE, "GOLD", "GOLDEN");
        assertThat(segmentCount).isEqualTo(1);
    }

    @Test
    void sqlInjectionAttemptOnSearch() {
        // sql injection attempt via `q` string using ilike match
        String maliciousQuery = "x%' OR 1=1 --";
        List<Member> result = repo.search(maliciousQuery, null, null, null, 10, 0);
        assertThat(result).isEmpty();

        // sql injection attempt via tier
        List<Member> resultTier = repo.search(null, null, "x%' OR 1=1 --", null, 10, 0);
        assertThat(resultTier).isEmpty();

        // sql injection attempt via segment
        List<Member> resultSegment = repo.search(null, null, null, "x%' OR 1=1 --", 10, 0);
        assertThat(resultSegment).isEmpty();

        // ensure the table is still alive
        assertThat(repo.count(null, null, null, null)).isEqualTo(3);
    }
}
