package io.loyaltyhub.wallet.application;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.loyaltyhub.common.auth.ActorContext;
import io.loyaltyhub.common.auth.ActorHolder;
import io.loyaltyhub.common.auth.Role;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.infra.WalletRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookWalletAdjustmentIT {

    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        PG.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository wallets;

    @Test
    @DisplayName("[TB-WAL-ADJ-006] Rifiuto rettifica su membro ANONYMIZED (Q-127)")
    void cannotAdjustAnonymizedMember() {
        ActorHolder.set(new ActorContext(Role.ADMIN, "admin"));

        // Roberto is ANONYMIZED in seed (MBR-000008)
        String anonymizedMemberId = "MBR-000008";

        assertThatThrownBy(() -> walletService.adjustBalance(anonymizedMemberId, "PTS", "CREDIT", 100, "GOODWILL", "Prova per anonimizzato"))
                .isInstanceOf(LhException.class)
                .satisfies(e -> {
                    LhException ex = (LhException) e;
                    assertThat(ex.code()).isEqualTo("MEMBER_ANONYMIZED");
                });
    }

    @Test
    @DisplayName("[TB-WAL-ADJ-005] Rifiuto rettifica valuta STS non spendibile (Q-46)")
    void cannotAdjustStsCurrency() {
        ActorHolder.set(new ActorContext(Role.ADMIN, "admin"));

        String memberId = "MBR-000004";

        assertThatThrownBy(() -> walletService.adjustBalance(memberId, "STS", "CREDIT", 100, "GOODWILL", "Prova STS rettifica"))
                .isInstanceOf(LhException.class)
                .satisfies(e -> {
                    LhException ex = (LhException) e;
                    assertThat(ex.code()).isEqualTo("CURRENCY_NOT_ADJUSTABLE");
                });
    }
}
