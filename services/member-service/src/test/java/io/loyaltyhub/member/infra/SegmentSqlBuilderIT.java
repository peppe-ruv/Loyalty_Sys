package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.Segment;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentSqlBuilderIT {

    private static EmbeddedPostgres pg;
    private static JdbcClient jdbc;
    private static SegmentRepository repo;
    private static ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        jdbc = JdbcClient.create(pg.getPostgresDatabase());
        repo = new SegmentRepository(jdbc, mapper);

        jdbc.sql("""
                CREATE SCHEMA IF NOT EXISTS member;
                """).update();
        jdbc.sql("SET search_path TO member;").update();
        jdbc.sql("""
                CREATE TABLE segment (
                    id text PRIMARY KEY,
                    code text,
                    name text,
                    description text,
                    type text,
                    criteria jsonb,
                    status text,
                    member_count int,
                    refreshed_at timestamp,
                    version int,
                    created_at timestamp,
                    updated_at timestamp,
                    created_by text,
                    updated_by text
                );
                """).update();

        // Insert dummy data
        jdbc.sql("INSERT INTO segment (id, code, name, type, status) VALUES ('SEG-01', 'CODE-A', 'First Segment', 'DYNAMIC', 'ACTIVE')").update();
        jdbc.sql("INSERT INTO segment (id, code, name, type, status) VALUES ('SEG-02', 'CODE-B', 'Second Segment', 'STATIC', 'ARCHIVED')").update();
        jdbc.sql("INSERT INTO segment (id, code, name, type, status) VALUES ('SEG-03', 'CODE-C', 'Third Seg', 'DYNAMIC', 'ACTIVE')").update();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void listFiltersWithSqlWhere() {
        List<Segment> all = repo.list(null, null, null);
        assertThat(all).hasSize(3);

        List<Segment> active = repo.list(null, null, "ACTIVE");
        assertThat(active).hasSize(2).extracting(Segment::code).containsExactly("CODE-A", "CODE-C");

        List<Segment> dynamic = repo.list(null, "DYNAMIC", null);
        assertThat(dynamic).hasSize(2).extracting(Segment::code).containsExactly("CODE-A", "CODE-C");

        List<Segment> matchFirst = repo.list("first", null, null);
        assertThat(matchFirst).hasSize(1).extracting(Segment::code).containsExactly("CODE-A");
    }

    @Test
    void sqlInjectionAttemptOnList() {
        String maliciousQuery = "CODE-A'; DROP TABLE segment--";
        List<Segment> result = repo.list(maliciousQuery, null, null);
        assertThat(result).isEmpty();

        assertThat(repo.list(null, null, null)).hasSize(3);
    }
}
