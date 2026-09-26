package io.loyaltyhub.campaign.testbook;

import io.loyaltyhub.campaign.engine.MemberSnapshot;
import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doppia lettura di {@code member.registered/updated} {@code :1} e {@code :2} (ADR-032, docs/18 §3.4, Q-346): lo
 * snapshot della campagna prende anno di nascita e provincia dalla {@code :2}, ricava l'anno dalla data della
 * {@code :1}, e un fatto che non porta un campo non lo cancella.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberFactsV2IT {

    private static final EmbeddedPostgres PG = CmpItSupport.startPg();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MemberSnapshotRepository snapshots;

    private CmpItSupport it;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=campaign");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @BeforeAll
    void setUp() {
        it = new CmpItSupport(port);
    }

    @AfterAll
    void tearDown() throws Exception {
        it.close();
        PG.close();
    }

    @Test
    @DisplayName("member.registered:2 porta anno e provincia; un member.updated:1 successivo non li perde")
    void v2ThenV1() {
        it.fact("member.registered", "MBR-990001", Map.of("memberId", "MBR-990001", "status", "ACTIVE",
                "registeredAt", "2026-01-15T10:00:00Z", "locale", "it", "birthYear", 1990, "province", "TO",
                "attributes", Map.of()));
        MemberSnapshot first = awaitSnapshot("MBR-990001", s -> s.province() != null);
        assertThat(first.birthYear()).isEqualTo(1990);
        assertThat(first.province()).isEqualTo("TO");
        assertThat(first.birthDate()).isNull();

        it.fact("member.updated", "MBR-990001", Map.of("memberId", "MBR-990001", "status", "ACTIVE",
                "labels", java.util.List.of("profile-complete"), "attributes", Map.of()));
        MemberSnapshot after = awaitSnapshot("MBR-990001", s -> s.labels().contains("profile-complete"));
        assertThat(after.birthYear()).isEqualTo(1990);
        assertThat(after.province()).isEqualTo("TO");
    }

    @Test
    @DisplayName("member.registered:1 con la data di nascita: l'anno si ricava dalla data")
    void v1DerivesYear() {
        it.fact("member.registered", "MBR-990002", Map.of("memberId", "MBR-990002", "status", "ACTIVE",
                "registeredAt", "2026-01-15T10:00:00Z", "birthDate", "1985-03-02", "attributes", Map.of()));
        MemberSnapshot s = awaitSnapshot("MBR-990002", x -> x.birthYear() != null);
        assertThat(s.birthYear()).isEqualTo(1985);
        assertThat(s.province()).isNull();
    }

    private MemberSnapshot awaitSnapshot(String memberId, java.util.function.Predicate<MemberSnapshot> ready) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            Optional<MemberSnapshot> s = snapshots.findById(memberId);
            if (s.isPresent() && ready.test(s.get())) {
                return s.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("snapshot di " + memberId + " non pronto entro 20 s: " + snapshots.findById(memberId));
    }
}
