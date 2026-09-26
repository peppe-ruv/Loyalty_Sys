package io.loyaltyhub.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Facciata delle metriche custom (docs/06 §8): {@code lh_events_consumed_total{type}},
 * {@code lh_events_published_total{type}}, {@code lh_outbox_pending}, {@code lh_handler_seconds{type}}.
 */
public class LhMetrics {

    private final MeterRegistry registry;

    public LhMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void eventConsumed(String type) {
        registry.counter("lh_events_consumed_total", "type", type).increment();
    }

    public void eventPublished(String type) {
        registry.counter("lh_events_published_total", "type", type).increment();
    }

    public void eventDlq(String type, String errorCode) {
        registry.counter("lh_events_dlq_total", "type", type, "errorCode", errorCode).increment();
    }

    /** Registra la durata dell'elaborazione di un handler per {@code type}. */
    public void handlerTime(String type, long nanos) {
        Timer.builder("lh_handler_seconds").tag("type", type).register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Registra il gauge {@code lh_outbox_pending}: il valore è letto a ogni raccolta (nessun lavoro in background). Un
     * errore di lettura (DB non raggiungibile) vale {@code NaN}, non un'eccezione della raccolta.
     */
    public void outboxPending(java.util.function.LongSupplier pending) {
        io.micrometer.core.instrument.Gauge.builder("lh_outbox_pending", () -> {
            try {
                return pending.getAsLong();
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }).strongReference(true).register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }
}
