package io.loyaltyhub.wallet;

import io.loyaltyhub.wallet.application.EditionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1"})
class EditionCloseDryRunIT {

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

    @Autowired
    private EditionService editionService;

    @Test
    void testEditionCloseDryRun() {
        var result = editionService.closeEdition("ED-2026", true);

        // Assert returned values
        assertThat(result.summary().retained()).isGreaterThanOrEqualTo(0);
        assertThat(result.summary().downgraded()).isGreaterThanOrEqualTo(0);

        var stefano = result.members().stream()
                .filter(m -> "MBR-000006".equals(m.memberId()))
                .findFirst()
                .orElseThrow();

        assertThat(stefano.currentTier()).isEqualTo("GOLD");
        assertThat(stefano.newTier()).isEqualTo("SILVER");
        assertThat(stefano.outcome().name()).isEqualTo("DOWNGRADED");
    }
}
