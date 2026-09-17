package it.iren.loyalty.catalogredemption.domain;

import it.iren.loyalty.common.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'addebito sul ledger è una chiamata a un altro servizio: quando il riscatto fallisce **dopo**
 * l'addebito — il caso classico è il lotto di codici esaurito — il rollback della transazione locale
 * non lo annulla. Senza compensazione il membro resta senza punti e senza premio (RF-15).
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class RedemptionCompensationIntegrationTest extends PostgresIntegrationTest {

    /** Ledger finto: registra addebiti e storni, così la compensazione si vede. */
    static class LedgerSpia implements LedgerPort {
        final List<String> addebiti = new ArrayList<>();
        final List<String> storni = new ArrayList<>();

        @Override public void debit(String memberId, String actionKey, long units, String reason) { addebiti.add(actionKey); }
        @Override public void reverse(String actionKey) { storni.add(actionKey); }

        void azzera() { addebiti.clear(); storni.clear(); }
    }

    @TestConfiguration
    static class Adattatori {
        @Bean @Primary LedgerSpia ledgerSpia() { return new LedgerSpia(); }
    }

    @Autowired RedemptionService redemptions;
    @Autowired LedgerSpia ledger;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean KafkaTemplate<String, byte[]> kafka;

    /** Il contesto è condiviso fra i test: la spia va azzerata, altrimenti si legge quella di prima. */
    @BeforeEach
    void azzeraLaSpia() {
        ledger.azzera();
    }

    /** Premio a buono che pesca da un lotto di codici: se il lotto è vuoto, il riscatto fallisce dopo l'addebito. */
    private UUID premioConLotto(String poolId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalogredemption.reward(id, name, type, value_eur, points_cost, min_tier_order, stock, status, coupon_pool_id, code_validity_days) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, "Buono 10 €", "VOUCHER", new java.math.BigDecimal("10.00"), 1000, 0, -1, "ACTIVE", poolId, 0);
        return id;
    }

    @Test
    void seIlLottoDiCodiciEVuotoLAddebitoVieneStornato() {
        UUID premio = premioConLotto("lotto-vuoto-" + UUID.randomUUID());
        String memberId = "m-" + UUID.randomUUID();

        assertThatThrownBy(() -> redemptions.redeem(memberId, 0, Set.of(), 5000, premio))
                .isInstanceOf(RedemptionService.RedemptionRejected.class)
                .hasMessageContaining("COUPON_POOL_EMPTY");

        assertThat(ledger.addebiti).hasSize(1);
        assertThat(ledger.storni).containsExactlyElementsOf(ledger.addebiti);
    }

    @Test
    void unRiscattoRiuscitoNonStornaNulla() {
        String poolId = "lotto-pieno-" + UUID.randomUUID();
        UUID premio = premioConLotto(poolId);
        jdbc.update("INSERT INTO catalogredemption.coupon(pool_id, code) VALUES (?,?)", poolId, "CODICE-1");
        String memberId = "m-" + UUID.randomUUID();

        var esito = redemptions.redeem(memberId, 0, Set.of(), 5000, premio);

        assertThat(esito.code()).isEqualTo("CODICE-1");
        assertThat(ledger.storni).isEmpty();
    }
}
