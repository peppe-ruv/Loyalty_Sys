package io.loyaltyhub.common.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dichiarazione dei 5 topic (ADR-004, ADR-028) con la forma di {@link LoyaltyHubProperties.TopicSettings}: stesso
 * codice per il profilo {@code local} di lh-common e per l'hub su broker reale (F2-EVT-04). I nomi non cambiano:
 * cambiano solo partizioni, repliche e retention. Una configurazione impossibile ferma l'avvio invece di creare
 * topic sbagliati.
 */
public final class LhTopics {

    private LhTopics() {
    }

    /** I 5 topic, nell'ordine di {@link LoyaltyHubProperties.Topics#all()}. */
    public static NewTopic[] newTopics(LoyaltyHubProperties props) {
        LoyaltyHubProperties.TopicSettings s = props.getTopicSettings();
        validate(props);
        return props.getTopics().byKey().entrySet().stream()
                .map(e -> {
                    TopicBuilder b = TopicBuilder.name(e.getValue())
                            .partitions(s.getPartitions())
                            .replicas(s.getReplicas())
                            .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retentionFor(s, e.getKey())));
                    if (s.getMinInsyncReplicas() != null) {
                        b.config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(s.getMinInsyncReplicas()));
                    }
                    return b.build();
                })
                .toArray(NewTopic[]::new);
    }

    /** Retention del topic con chiave logica {@code key}: quella dedicata se presente, altrimenti quella comune. */
    public static long retentionFor(LoyaltyHubProperties.TopicSettings s, String key) {
        Long specific = s.getRetentionMsByTopic().get(key);
        return specific != null ? specific : s.getRetentionMs();
    }

    static void validate(LoyaltyHubProperties props) {
        LoyaltyHubProperties.TopicSettings s = props.getTopicSettings();
        if (s.getPartitions() < 1) {
            throw new IllegalStateException("loyaltyhub.topic-settings.partitions deve essere almeno 1: " + s.getPartitions());
        }
        if (s.getReplicas() < 1) {
            throw new IllegalStateException("loyaltyhub.topic-settings.replicas deve essere almeno 1: " + s.getReplicas());
        }
        if (s.getMinInsyncReplicas() != null
                && (s.getMinInsyncReplicas() < 1 || s.getMinInsyncReplicas() > s.getReplicas())) {
            throw new IllegalStateException("loyaltyhub.topic-settings.min-insync-replicas deve stare tra 1 e replicas ("
                    + s.getReplicas() + "): " + s.getMinInsyncReplicas());
        }
        Set<String> keys = props.getTopics().byKey().keySet();
        Set<String> unknown = new TreeSet<>(s.getRetentionMsByTopic().keySet());
        unknown.removeAll(keys);
        if (!unknown.isEmpty()) {
            // Un refuso (es. "fact") lascerebbe in silenzio la retention comune: meglio fermarsi.
            throw new IllegalStateException("loyaltyhub.topic-settings.retention-ms-by-topic: chiavi sconosciute " + unknown
                    + " (ammesse: " + keys + ")");
        }
        checkRetention("retention-ms", s.getRetentionMs());
        for (Map.Entry<String, Long> e : s.getRetentionMsByTopic().entrySet()) {
            checkRetention("retention-ms-by-topic." + e.getKey(), e.getValue());
        }
    }

    /** Kafka ammette una retention positiva oppure {@code -1} (illimitata). */
    private static void checkRetention(String name, Long value) {
        if (value == null || (value < 1 && value != -1)) {
            throw new IllegalStateException("loyaltyhub.topic-settings." + name + " deve essere positiva o -1: " + value);
        }
    }

    /** Concorrenza dei listener valida (almeno 1). */
    static int concurrency(LoyaltyHubProperties props) {
        int c = props.getConsumer().getConcurrency();
        if (c < 1) {
            throw new IllegalStateException("loyaltyhub.consumer.concurrency deve essere almeno 1: " + c);
        }
        return c;
    }
}
