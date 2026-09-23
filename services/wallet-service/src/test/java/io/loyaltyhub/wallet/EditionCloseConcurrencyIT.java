package io.loyaltyhub.wallet;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.application.EditionService;
import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chiusura di edizione sotto concorrenza (F-TIER-04): due applicazioni simultanee si serializzano sulla riga
 * dell'edizione, e un accredito STS in volo viene visto dalla chiusura invece di essere azzerato. Contesto DB
 * proprio: chiude ED-2026.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1"})
class EditionCloseConcurrencyIT {

    static EmbeddedPostgres pg;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        try {
            pg = EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        reg.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        reg.add("spring.datasource.username", () -> "postgres");
        reg.add("spring.datasource.password", () -> "postgres");
        reg.add("spring.flyway.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        reg.add("spring.flyway.user", () -> "postgres");
        reg.add("spring.flyway.password", () -> "postgres");
        reg.add("spring.flyway.schemas", () -> "wallet");
        reg.add("spring.datasource.hikari.schema", () -> "wallet");
    }

    @Autowired EditionService editionService;
    @Autowired EditionRepository editions;
    @Autowired MemberTierRepository memberTiers;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;

    @Test
    void concurrentClosesAreSerializedAndSeeInFlightStsGrant() throws Exception {
        long activeMembers = jdbc.sql("SELECT count(*) FROM member_tier WHERE member_status = 'ACTIVE'")
                .query(Long.class).single();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> closes = new ArrayList<>();
        // Accredito STS di Stefano (GOLD, 650) in volo: +2500 → 3150 ≥ soglia GOLD. Transazione tenuta aperta.
        try (Connection grant = dataSource.getConnection()) {
            grant.setAutoCommit(false);
            try (PreparedStatement ps = grant.prepareStatement(
                    "UPDATE member_tier SET period_sts = period_sts + 2500 WHERE member_id = 'MBR-000006'")) {
                ps.executeUpdate();
            }
            for (int i = 0; i < 2; i++) {
                closes.add(pool.submit(() -> {
                    try {
                        return editionService.closeEdition("ED-2026", false);
                    } catch (LhException e) {
                        return e;
                    }
                }));
            }
            Thread.sleep(1_500); // le chiusure sono in attesa del lock sulla riga di Stefano / sull'edizione
            assertThat(closes).noneMatch(Future::isDone);
            grant.commit();
        }

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : closes) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // Esattamente una chiusura applicata; l'altra riceve EDITION_ALREADY_CLOSED.
        List<EditionService.ClosePreviewResult> applied = outcomes.stream()
                .filter(EditionService.ClosePreviewResult.class::isInstance)
                .map(EditionService.ClosePreviewResult.class::cast).toList();
        List<LhException> rejected = outcomes.stream()
                .filter(LhException.class::isInstance).map(LhException.class::cast).toList();
        assertThat(applied).hasSize(1);
        assertThat(rejected).singleElement()
                .satisfies(e -> assertThat(e.code()).isEqualTo("EDITION_ALREADY_CLOSED"));

        // Nessuna riga di storico duplicata.
        long historyRows = jdbc.sql("SELECT count(*) FROM tier_history WHERE edition_code = 'ED-2026'")
                .query(Long.class).single();
        assertThat(historyRows).isEqualTo(activeMembers);

        // La chiusura ha visto l'accredito (3150 STS) → Stefano mantiene GOLD invece di scendere a SILVER.
        EditionService.ClosePreviewMember stefano = applied.getFirst().members().stream()
                .filter(m -> m.memberId().equals("MBR-000006")).findFirst().orElseThrow();
        assertThat(stefano.periodSts()).isEqualTo(3150);
        assertThat(stefano.newTier()).isEqualTo("GOLD");
        assertThat(memberTiers.find("MBR-000006").orElseThrow().tierCode()).isEqualTo("GOLD");
        assertThat(memberTiers.find("MBR-000006").orElseThrow().periodSts()).isZero();

        assertThat(editions.findByCode("ED-2026").orElseThrow().status()).isEqualTo(Edition.CLOSED);
        assertThat(editions.findByCode("ED-2027").orElseThrow().status()).isEqualTo(Edition.ACTIVE);
    }
}
