package io.loyaltyhub.common.inbox;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Registro {@code processed_event} per la deduplica lato consumer (docs/06 §1). Append-only per {@code (consumer, event_id)}. */
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marca l'evento come processato dal consumer. Ritorna {@code true} se è la <em>prima</em> volta,
     * {@code false} se era già presente (duplicato). {@code ON CONFLICT DO NOTHING}: atomico e idempotente.
     */
    public boolean markProcessed(String consumer, String eventId) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)
                        ON CONFLICT (consumer, event_id) DO NOTHING
                        """)
                .params(consumer, eventId)
                .update();
        return inserted == 1;
    }

    public boolean isProcessed(String consumer, String eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE consumer = ? AND event_id = ?")
                .params(consumer, eventId)
                .query(Long.class)
                .single() > 0;
    }
}
