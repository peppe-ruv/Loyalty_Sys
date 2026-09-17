package io.loyaltyhub.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Metriche di business del programma (RF-117), esposte da ogni servizio su {@code /actuator/prometheus} accanto a
 * quelle tecniche (JVM, HTTP, Kafka, datasource). Nomi stabili con prefisso {@code loyalty_}; le etichette sono a
 * cardinalità bassa (mai id membro): fonte, tipo azione, campagna, wallet, stato, concorso. Alimentano i cruscotti
 * Grafana (RF-46, RF-119) e gli alert SLO (RF-120).
 */
public final class LoyaltyMetrics {
    private final MeterRegistry registry;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public LoyaltyMetrics(MeterRegistry registry) { this.registry = registry; }

    /** Azioni ricevute dagli adattatori: status = ACCEPTED | DUPLICATE | REJECTED. */
    public void actionReceived(String source, String actionType, String status) {
        count("loyalty_actions_received_total", "source", source, "action_type", actionType, "status", status);
    }
    /** Effetti di campagna applicati: type = ADD_UNITS | GIVE_REWARD | ... */
    public void campaignEffect(String campaignId, String type) { count("loyalty_campaign_effects_total", "campaign", campaignId, "type", type); }
    /** Azioni senza campagna attiva (RF-03): da tenere sotto soglia. */
    public void noCampaign(String actionType) { count("loyalty_actions_without_campaign_total", "action_type", actionType); }
    /** Unità movimentate sul ledger. */
    public void movement(String wallet, String kind, long amount) {
        count("loyalty_movements_total", "wallet", wallet, "kind", kind);
        registry.counter("loyalty_units_total", Tags.of("wallet", wallet, "kind", kind)).increment(Math.abs(amount));
    }
    /** Riscatti per stato finale della richiesta (CONFIRMED, INSUFFICIENT_BALANCE, OUT_OF_STOCK...). */
    public void redemption(String status, String rewardType) { count("loyalty_redemptions_total", "status", status, "reward_type", rewardType); }
    /** Giocate instant win e giri della ruota. */
    public void play(String contestId, boolean won) { count("loyalty_contest_plays_total", "contest", contestId, "won", String.valueOf(won)); }
    public void spin(String wheelId, boolean won) { count("loyalty_wheel_spins_total", "wheel", wheelId, "won", String.valueOf(won)); }
    public void achievementCompleted(String achievementId) { count("loyalty_achievements_completed_total", "achievement", achievementId); }
    public void challengeCompleted(String challengeId) { count("loyalty_challenges_completed_total", "challenge", challengeId); }
    public void badgeGranted(String badgeCode) { count("loyalty_badges_granted_total", "badge", badgeCode); }
    public void memberEvent(String event) { count("loyalty_member_events_total", "event", event); }
    public void webhook(String subscriptionId, boolean delivered) { count("loyalty_webhook_deliveries_total", "subscription", subscriptionId, "delivered", String.valueOf(delivered)); }

    // ---- Loyalty 4.0 (RF-125..RF-136) ----
    /** Decisioni per azione primaria, esperimento e variante (RF-127, RF-134). */
    public void decision(String primaryAction, String experimentId, String variant) { count("loyalty_decisions_total", "action", primaryAction, "experiment", experimentId, "variant", variant); }
    /** Candidati scartati per codice motivo: la distribuzione dei motivi guida la taratura della policy. */
    public void decisionRejected(String action, String reasonCode) { count("loyalty_decision_rejections_total", "action", action, "reason", reasonCode); }
    public void decisionExecuted(String action, boolean ok) { count("loyalty_decision_effects_total", "action", action, "ok", String.valueOf(ok)); }
    /** Chiamate ai provider di previsione (RF-130), con esito. */
    public void prediction(String provider, boolean ok) { count("loyalty_predictions_total", "provider", provider, "ok", String.valueOf(ok)); }
    public void experimentExposure(String experimentId, String variant) { count("loyalty_experiment_exposures_total", "experiment", experimentId, "variant", variant); }
    /** Valutazioni di rischio per livello (RF-131). */
    public void risk(String level, String topReason) { count("loyalty_risk_assessments_total", "level", level, "reason", topReason == null ? "-" : topReason); }
    /** Consegne per canale ed esito (RF-132). */
    public void delivery(String channel, String status) { count("loyalty_deliveries_total", "channel", channel, "status", status); }
    public void consent(String purpose, boolean granted) { count("loyalty_consents_total", "purpose", purpose, "granted", String.valueOf(granted)); }
    public void identity(String event) { count("loyalty_identity_events_total", "event", event); }

    /**
     * Consumo del budget giornaliero di unità della policy decisionale (RF-128): quanto è stato concesso e qual è il
     * tetto. Due gauge, così il cruscotto mostra la percentuale e l'alert scatta prima che il budget finisca.
     */
    public void decisionBudget(String policyId, long granted, long budget) {
        gauge("loyalty_decision_units_granted_today", granted, "policy", policyId);
        gauge("loyalty_decision_units_budget", budget, "policy", policyId);
    }

    /** Stato dell'interruttore automatico verso un servizio esterno: 0 chiuso (tutto bene), 1 aperto, 2 in prova. */
    public void circuitBreaker(String name, String state) {
        gauge("loyalty_circuit_breaker_state", switch (state) { case "OPEN" -> 1; case "HALF_OPEN" -> 2; default -> 0; }, "name", name);
    }

    /** Gauge impostabili: istanti vincenti residui per concorso, righe outbox non pubblicate, scorta lotti coupon, lag segmenti. */
    public void gauge(String name, long value, String... tags) {
        String key = name + Tags.of(tags);
        AtomicLong holder = gauges.computeIfAbsent(key, k -> {
            AtomicLong a = new AtomicLong();
            Gauge.builder(name, a, AtomicLong::get).tags(tags).register(registry);
            return a;
        });
        holder.set(value);
    }
    public void gauge(String name, Supplier<Number> supplier, String... tags) { Gauge.builder(name, supplier, s -> s.get().doubleValue()).tags(tags).register(registry); }

    /** Timer per le operazioni sincrone critiche (giocata, riscatto): alimentano gli SLO di latenza. */
    public Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().register(registry);
    }

    private void count(String name, String... tags) { Counter.builder(name).tags(tags).register(registry).increment(); }
}
