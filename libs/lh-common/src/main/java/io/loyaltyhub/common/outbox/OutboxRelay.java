package io.loyaltyhub.common.outbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Relay dell'outbox (docs/06 §1): pubblica su Kafka le righe non ancora pubblicate e le marca.
 * Se Kafka è irraggiungibile la riga resta in {@code outbox} e viene ripubblicata al ritorno del broker
 * (nessuna perdita — criterio di accettazione M0). {@code FOR UPDATE SKIP LOCKED}: più istanze non si pestano.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final LhMetrics metrics;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                       LhMetrics metrics, int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${loyaltyhub.outbox.relay-interval-ms:500}")
    @Transactional(propagation = Propagation.REQUIRED)
    public void publishBatch() {
        List<Row> rows = jdbc.sql("""
                        SELECT id, topic, msg_key, type, payload::text AS payload
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """)
                .param(batchSize)
                .query(Row.class)
                .list();

        for (Row row : rows) {
            try {
                kafka.send(toRecord(row)).get();
                jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ?").param(row.id()).update();
                metrics.eventPublished(row.type());
            } catch (Exception e) {
                // Non marco pubblicato: la riga verrà ritentata al prossimo giro (Kafka down → nessuna perdita).
                log.warn("Pubblicazione outbox fallita per id={} type={}: {}", row.id(), row.type(), e.toString());
                throw new OutboxPublishException(e);
            }
        }
    }

    private ProducerRecord<String, String> toRecord(Row row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.msgKey(), row.payload());
        record.headers().add(new RecordHeader(LhHeaders.TYPE, bytes(row.type())));
        header(record, LhHeaders.CORRELATION_ID, textField(row.payload(), "lhcorrelationid"));
        header(record, LhHeaders.CAUSATION_ID, textField(row.payload(), "lhcausationid"));
        header(record, LhHeaders.HOP, textField(row.payload(), "lhhop"));
        header(record, LhHeaders.ACTOR, textField(row.payload(), "lhactor"));
        record.headers().add(new RecordHeader("content-type", bytes(LhHeaders.CONTENT_TYPE_VALUE)));
        return record;
    }

    private void header(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(new RecordHeader(name, bytes(value)));
        }
    }

    private String textField(String payload, String field) {
        try {
            JsonNode node = mapper.readTree(payload).get(field);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Riga dell'outbox letta dal relay. */
    public record Row(UUID id, String topic, String msgKey, String type, String payload) {
    }

    /** Segnala il fallimento di pubblicazione così la transazione del batch fa rollback e riprova. */
    public static class OutboxPublishException extends RuntimeException {
        public OutboxPublishException(Throwable cause) {
            super(cause);
        }
    }
}
