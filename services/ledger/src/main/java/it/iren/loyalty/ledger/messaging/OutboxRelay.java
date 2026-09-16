package it.iren.loyalty.ledger.messaging;

import it.iren.loyalty.ledger.domain.Outbox;
import it.iren.loyalty.ledger.domain.Repositories;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Relay dell'outbox verso Kafka. In produzione può essere sostituito da Debezium (CDC) senza cambiare il dominio. */
@Component
public class OutboxRelay {
    private final Repositories.OutboxRepository outbox;
    private final KafkaTemplate<String, byte[]> kafka;

    public OutboxRelay(Repositories.OutboxRepository outbox, KafkaTemplate<String, byte[]> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay-ms:500}")
    @Transactional
    public void relay() {
        for (Outbox o : outbox.findTop500ByPublishedAtIsNullOrderByCreatedAt()) {
            kafka.send(o.getTopic(), o.getKey(), o.getPayload()).join();
            o.markPublished();
        }
    }
}
