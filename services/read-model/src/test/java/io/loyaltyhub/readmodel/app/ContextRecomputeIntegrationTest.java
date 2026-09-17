package io.loyaltyhub.readmodel.app;

import io.loyaltyhub.common.test.PostgresIntegrationTest;
import io.loyaltyhub.readmodel.domain.ContextProjector;
import io.loyaltyhub.readmodel.domain.CustomerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il ricalcolo notturno deve riscrivere davvero i contesti fermi — è lì che le finestre mobili scadono — e lasciare
 * stare quelli che non cambiano, altrimenti ogni notte riscriverebbe l'intera base clienti per niente.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "readmodel.recompute.enabled=false"})
class ContextRecomputeIntegrationTest extends PostgresIntegrationTest {

    @Autowired ContextStore store;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean KafkaTemplate<String, byte[]> kafka;

    @BeforeEach
    void pulisci() {
        jdbc.update("DELETE FROM readmodel.member_summary");
        jdbc.update("DELETE FROM readmodel.customer_context");
    }

    /**
     * Stato di partenza: il contesto come il proiettore lo aveva scritto quando la transazione era fresca — finestre
     * piene — ma la transazione ha ormai 200 giorni. È esattamente ciò che si trova in produzione su un cliente fermo.
     */
    @Test void il_ricalcolo_fa_scadere_le_finestre_dei_contesti_fermi() {
        Instant duecentoGiorniFa = Instant.now().minus(Duration.ofDays(200));
        store.update("m-fermo", c -> {
            var conAzione = ContextProjector.onAction(c, "TRANSACTION", duecentoGiorniFa, "app", 80.0, "t1", Map.of());
            var beh = conAzione.behaviour();
            return new CustomerContext(conAzione.memberId(), conAzione.identity(), conAzione.loyalty(),
                    new CustomerContext.Behaviour(beh.recentActions(),
                            new CustomerContext.Rfm(0, 1, 1, 80.0, duecentoGiorniFa, duecentoGiorniFa),
                            Map.of("TRANSACTION", 1), beh.preferredChannel()),
                    conAzione.engagement(), conAzione.risk(), conAzione.consents(), conAzione.predictions(), conAzione.updatedAt());
        });
        assertThat(store.get("m-fermo").behaviour().rfm().frequency90d()).isEqualTo(1);

        int scritti = store.recomputeWindows(100);

        assertThat(scritti).isEqualTo(1);
        var dopo = store.get("m-fermo");
        assertThat(dopo.behaviour().rfm().frequency90d()).isZero();          // uscita dalla finestra dei 90 giorni
        assertThat(dopo.behaviour().rfm().frequency365d()).isEqualTo(1);     // ma non da quella dei 365
        assertThat(dopo.behaviour().actionCounts30d()).isEmpty();
    }

    /**
     * Un contesto senza finestre da far scadere non si tocca: ogni notte non si riscrive l'intera base clienti per
     * niente. (Un contesto con una transazione recente invece cambia, e giustamente: la recency avanza di un giorno
     * al giorno anche senza eventi.)
     */
    @Test void un_contesto_senza_finestre_da_far_scadere_non_viene_riscritto() {
        store.update("m-senza-storia", c -> ContextProjector.onTier(c, "PLUS", Instant.now()));

        assertThat(store.recomputeWindows(100)).isZero();
        assertThat(store.get("m-senza-storia").loyalty().tier()).isEqualTo("PLUS");
    }

    /** La cache è un accorgimento di prestazione: senza Redis il servizio legge dal database e risponde lo stesso. */
    @Test void senza_redis_la_lettura_funziona_dal_database() {
        store.update("m-senza-cache", c -> ContextProjector.onTier(c, "PLUS", Instant.now()));
        assertThat(store.get("m-senza-cache").loyalty().tier()).isEqualTo("PLUS");
    }
}
