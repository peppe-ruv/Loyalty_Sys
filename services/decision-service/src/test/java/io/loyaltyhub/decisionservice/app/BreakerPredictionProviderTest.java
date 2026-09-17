package io.loyaltyhub.decisionservice.app;

import io.loyaltyhub.decisionservice.domain.DecisionContext;
import io.loyaltyhub.decisionservice.domain.PredictionProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un provider di previsioni esterno che non risponde non deve far pagare il timeout a ogni decisione: dopo qualche
 * fallimento l'interruttore si apre e le chiamate successive falliscono subito, così il composito ricade sulle regole.
 */
class BreakerPredictionProviderTest {

    private static final DecisionContext CTX = DecisionContext.minimal("m1", "BASE");

    /** Provider finto: conta le chiamate davvero arrivate e risponde o si rompe su comando. */
    static class ProviderFinto implements PredictionProvider {
        final AtomicInteger chiamate = new AtomicInteger();
        volatile boolean rotto = true;

        @Override public String name() { return "ml-finto"; }
        @Override public Map<String, Double> predict(DecisionContext ctx, Set<String> keys) {
            chiamate.incrementAndGet();
            if (rotto) throw new IllegalStateException("timeout");
            return Map.of("churnRisk", 0.3);
        }
    }

    @Test void dopo_i_fallimenti_l_interruttore_si_apre_e_non_chiama_piu_il_provider() {
        var finto = new ProviderFinto();
        var breaker = new BreakerPredictionProvider(finto, 50, 4, Duration.ofSeconds(30), null);

        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> breaker.predict(CTX, Set.of("churnRisk"))).isInstanceOf(RuntimeException.class);
        assertThat(finto.chiamate).hasValue(4);
        assertThat(breaker.state()).isEqualTo("OPEN");

        assertThatThrownBy(() -> breaker.predict(CTX, Set.of("churnRisk")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interruttore aperto");
        assertThat(finto.chiamate).hasValue(4);   // la chiamata non è nemmeno partita
    }

    /** Passata la finestra di attesa l'interruttore riprova: se il provider è tornato, si richiude da solo. */
    @Test void quando_il_provider_torna_l_interruttore_si_richiude() throws Exception {
        var finto = new ProviderFinto();
        var breaker = new BreakerPredictionProvider(finto, 50, 2, Duration.ofMillis(100), null);

        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> breaker.predict(CTX, Set.of("churnRisk"))).isInstanceOf(RuntimeException.class);
        assertThat(breaker.state()).isEqualTo("OPEN");

        finto.rotto = false;
        Thread.sleep(250);
        for (int i = 0; i < 3; i++) assertThat(breaker.predict(CTX, Set.of("churnRisk"))).containsEntry("churnRisk", 0.3);
        assertThat(breaker.state()).isEqualTo("CLOSED");
    }

    /** Lo stato passa alle metriche: il cruscotto deve poter mostrare quando il motore sta decidendo senza previsioni. */
    @Test void lo_stato_arriva_alle_metriche() {
        var finto = new ProviderFinto();
        var stati = new java.util.ArrayList<String>();
        var breaker = new BreakerPredictionProvider(finto, 50, 2, Duration.ofSeconds(30), (nome, stato) -> stati.add(nome + "=" + stato));

        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> breaker.predict(CTX, Set.of("churnRisk"))).isInstanceOf(RuntimeException.class);

        assertThat(stati).first().isEqualTo("ml-finto=CLOSED");
        assertThat(stati).last().isEqualTo("ml-finto=OPEN");
    }
}
