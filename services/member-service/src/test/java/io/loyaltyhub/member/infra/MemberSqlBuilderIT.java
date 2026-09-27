package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemberSqlBuilderIT {

    private static EmbeddedPostgres pg;
    private static JdbcClient jdbc;
    private static MemberRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        jdbc = JdbcClient.create(pg.getPostgresDatabase());
        repo = new MemberRepository(jdbc);

        jdbc.sql("""
                CREATE SCHEMA IF NOT EXISTS member;
                """).update();
        jdbc.sql("SET search_path TO member;").update();
        jdbc.sql("""
                CREATE TABLE member (
                    id text PRIMARY KEY,
                    external_id text,
                    first_name text,
                    last_name text,
                    nickname text,
                    email text,
                    phone text,
                    birth_date date,
                    gender text,
                    city text,
                    status text,
                    channel text,
                    registered_at timestamp,
                    referral_code text,
                    referred_by text,
                    referral_completed_at timestamp,
                    consents jsonb,
                    attributes jsonb,
                    labels text[],
                    avatar_seed text,
                    profile_completed_at timestamp,
                    version bigint
                );
                CREATE TABLE member_projection (
                    member_id text PRIMARY KEY,
                    tier_code text
                );
                CREATE TABLE segment (
                    id text PRIMARY KEY,
                    code text
                );
                CREATE TABLE segment_member (
                    segment_id text,
                    member_id text
                );
                """).update();

        // Insert dummy data
        jdbc.sql("INSERT INTO member (id, first_name, email, status) VALUES ('MBR-01', 'Alice', 'alice@test.com', 'ACTIVE')").update();
        jdbc.sql("INSERT INTO member_projection (member_id, tier_code) VALUES ('MBR-01', 'GOLD')").update();

        jdbc.sql("INSERT INTO member (id, first_name, email, status) VALUES ('MBR-02', 'Bob', 'bob@test.com', 'INACTIVE')").update();
        jdbc.sql("INSERT INTO member_projection (member_id, tier_code) VALUES ('MBR-02', 'BASE')").update();

        jdbc.sql("INSERT INTO member (id, first_name, email, status) VALUES ('MBR-03', 'Eve', 'eve@test.com', 'ACTIVE')").update();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    void searchFiltersWithSqlWhere() {
        List<Member> all = repo.search(null, null, null, null, 10, 0);
        assertThat(all).hasSize(3);

        List<Member> active = repo.search(null, MemberStatus.ACTIVE, null, null, 10, 0);
        assertThat(active).hasSize(2).extracting(Member::id).containsExactly("MBR-01", "MBR-03");

        List<Member> gold = repo.search(null, null, "GOLD", null, 10, 0);
        assertThat(gold).hasSize(1).extracting(Member::id).containsExactly("MBR-01");

        List<Member> matchAlice = repo.search("alice", null, null, null, 10, 0);
        assertThat(matchAlice).hasSize(1).extracting(Member::id).containsExactly("MBR-01");
    }

    @Test
    void countFiltersWithSqlWhere() {
        long all = repo.count(null, null, null, null);
        assertThat(all).isEqualTo(3);

        long active = repo.count(null, MemberStatus.ACTIVE, null, null);
        assertThat(active).isEqualTo(2);

        long gold = repo.count(null, null, "GOLD", null);
        assertThat(gold).isEqualTo(1);
    }

    @Test
    void sqlInjectionAttemptOnSearch() {
        // sql injection attempt via `q` string using ilike match
        String maliciousQuery = "id; DROP TABLE member";
        List<Member> result = repo.search(maliciousQuery, null, null, null, 10, 0);
        assertThat(result).isEmpty();

        // ensure the table is still alive
        assertThat(repo.count(null, null, null, null)).isEqualTo(3);
    }
}
