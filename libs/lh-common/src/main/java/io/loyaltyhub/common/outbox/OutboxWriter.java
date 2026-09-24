package io.loyaltyhub.common.outbox;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * Scrive un evento nella tabella {@code outbox} <em>dentro la transazione del chiamante</em> (docs/06 §1).
 * La pubblicazione su Kafka avviene poi in modo asincrono e affidabile da {@link OutboxRelay}:
 * così un duplicato è tollerato, ma un'azione persa no (CLAUDE.md §3, Idempotenza).
 */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final LoyaltyHubProperties props;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper mapper, LoyaltyHubProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    /** Accoda l'evento sul topic della sua famiglia, con chiave di partizione derivata dal subject. */
    public UUID write(LhEvent<?> event) {
        LhFamily family = event.family();
        if (family == null) {
            throw new IllegalArgumentException("type non riconosciuto: " + event.type());
        }
        return write(props.topicFor(family), event.partitionKey(), event);
    }

    /**
     * Accoda l'evento su un topic e con una chiave espliciti (audit: {@code entityType:entityId}).
     * {@code created_at = clock_timestamp()} e non {@code now()}: {@code now()} è l'inizio della transazione, uguale
     * per tutte le righe scritte insieme, e il relay (che ordina per {@code created_at}) le pubblicherebbe in ordine
     * casuale. Serve l'ordine di scrittura: es. l'accredito PTS di un acquisto deve precedere quello STS che può far
     * salire di livello, altrimenti i PTS prendono il moltiplicatore del livello nuovo.
     */
    public UUID write(String topic, String key, LhEvent<?> event) {
        UUID id = UUID.randomUUID();
        String payload = serialize(event);
        jdbc.sql("""
                        INSERT INTO outbox (id, topic, msg_key, type, payload, created_at)
                        VALUES (?, ?, ?, ?, cast(? AS jsonb), clock_timestamp())
                        """)
                .params(id, topic, key, event.type(), payload)
                .update();
        return id;
    }

    private String serialize(LhEvent<?> event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("evento non serializzabile: " + event.type(), e);
        }
    }
}
