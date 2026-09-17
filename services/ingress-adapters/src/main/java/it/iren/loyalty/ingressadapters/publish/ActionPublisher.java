package it.iren.loyalty.ingressadapters.publish;

import io.cloudevents.CloudEvent;
import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pubblica l'evento canonico sul topic delle azioni; gli scarti vanno sulla DLQ con il motivo (RF-45).
 *
 * <p>La pubblicazione <b>attende l'esito del broker</b>: con un invio a perdere, un rifiuto di Kafka
 * non arrivava a nessuno, la fonte riceveva comunque 202 e l'azione spariva — con la chiave di
 * idempotenza ormai consumata, nemmeno un rinvio l'avrebbe recuperata. Meglio un errore alla fonte,
 * che riproverà: i consumer sono idempotenti (RI-01, D08) e un duplicato non fa danno.
 */
@Component
public class ActionPublisher {

    /** Il broker non ha confermato: l'azione non è entrata nella piattaforma. */
    public static class PublishFailed extends RuntimeException {
        public PublishFailed(String message, Throwable cause) { super(message, cause); }
    }

    private final KafkaTemplate<String, byte[]> kafka;
    private final Duration ackTimeout;

    public ActionPublisher(KafkaTemplate<String, byte[]> kafka,
                           @Value("${ingress.publish.ack-timeout-ms:5000}") long ackTimeoutMs) {
        this.kafka = kafka;
        this.ackTimeout = Duration.ofMillis(ackTimeoutMs);
    }

    public void publish(CloudEvent event) {
        // chiave = membro: tutti gli eventi di un membro finiscono nella stessa partizione (ordine garantito)
        send(EventTypes.TOPIC_ACTIONS, CanonicalEvents.memberId(event), CanonicalEvents.serialize(event));
    }

    public void reject(String source, String memberId, RewardingAction action, String reason) {
        var event = CanonicalEvents.of(EventTypes.ACTION_V1 + ".rejected", "urn:iren:loyalty:source:" + source,
                "member:" + memberId, Map.of("reason", reason, "action", action));
        send(EventTypes.TOPIC_DLQ, memberId, CanonicalEvents.serialize(event));
    }

    private void send(String topic, String key, byte[] payload) {
        try {
            kafka.send(topic, key, payload).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailed("pubblicazione su " + topic + " interrotta", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishFailed("il broker non ha confermato la pubblicazione su " + topic, e);
        }
    }
}
