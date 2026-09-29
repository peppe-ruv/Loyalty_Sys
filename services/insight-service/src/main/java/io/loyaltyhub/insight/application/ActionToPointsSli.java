package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SLI «azione → punti» di ADR-036 (obiettivo: p95 &lt; 5 s), misurato da insight (M8.6a, F2-OBS-01).
 *
 * <p>Alla registrazione di un {@code wallet.points.earned} <em>nuovo</em> (un duplicato non arriva qui: l'inserimento è
 * idempotente su {@code event_id}) si legge dall'event store l'azione radice del tracciato, cioè l'evento
 * {@code ACTION} il cui {@code event_id} è il {@code lhcorrelationid} del fatto (docs/05 §2), e si registra nel timer
 * {@code lh_action_to_points_seconds} il tempo trascorso dalla <strong>registrazione in insight</strong> dell'azione:
 * copre la catena ingestion → campaign → wallet → insight, senza orologi confrontati tra servizi (nessuna chiamata
 * sincrona, regola 3) e senza dati personali nelle etichette (nessuna etichetta). Un accredito la cui azione radice
 * non è nell'event store (catena senza azione, azione già eliminata dalla retention) non produce
 * alcuna osservazione: non è un'azione che aspetta i suoi punti.
 *
 * <p>I bucket sono le soglie dell'SLO e le loro code ({@code 1 s … 60 s}): l'obiettivo di 5 s è un bucket esplicito, così
 * la quota di accrediti entro l'obiettivo si calcola in modo esatto.
 *
 * <p>SPEC-GAP: Q-523 — ADR-036 fissa l'obiettivo ma non dove si misura; si sceglie l'istante di registrazione
 * dell'azione in insight (non il {@code time} dell'evento, che può essere del passato o scelto dalla fonte).
 */
@Component
public class ActionToPointsSli {

    /** Nome del timer: in Prometheus diventa {@code lh_action_to_points_seconds_bucket} (base in secondi, M8.6a). */
    static final String METRIC = "lh_action_to_points_seconds";

    private final EventStoreRepository events;
    private final Timer timer;

    public ActionToPointsSli(MeterRegistry registry, EventStoreRepository events) {
        this.events = events;
        this.timer = Timer.builder(METRIC)
                .description("Tempo tra la registrazione in insight dell'azione radice e dell'accredito wallet.points.earned (SLI di ADR-036)")
                .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                        Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
                .register(registry);
    }

    /**
     * Registra l'osservazione per un accredito appena registrato.
     *
     * @param correlationId {@code lhcorrelationid} del fatto: l'id dell'azione radice; {@code null} = nessuna osservazione
     */
    public void onPointsEarned(String correlationId) {
        if (correlationId == null) {
            return;
        }
        events.secondsSinceAction(correlationId)
                .ifPresent(seconds -> timer.record(Duration.ofNanos(Math.round(Math.max(0d, seconds) * 1e9))));
    }
}
