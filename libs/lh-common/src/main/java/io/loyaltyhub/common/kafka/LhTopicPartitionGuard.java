package io.loyaltyhub.common.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Impedisce che l'avvio aumenti in silenzio le partizioni dei 5 topic (F2-EVT-04, ADR-028). {@link KafkaAdmin} aggiunge
 * partizioni quando la configurazione ne chiede più di quelle esistenti; così le chiavi {@code memberId} cambiano
 * partizione e gli eventi in volo di uno stesso membro possono essere consumati fuori ordine. Si aumenta solo dopo aver
 * svuotato i consumer e con {@code loyaltyhub.topic-settings.allow-partition-increase=true}.
 *
 * <p>Gira quando l'applicazione dichiara i topic ({@code create=true}), prima dell'inizializzazione di
 * {@link KafkaAdmin} (che avviene dopo la creazione dei singleton). Un broker irraggiungibile non ferma l'avvio: lo
 * gestisce {@link KafkaAdmin} come oggi; la guardia agisce solo su partizioni lette davvero.
 */
public class LhTopicPartitionGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LhTopicPartitionGuard.class);
    private static final long TIMEOUT_MS = 15_000;

    private final LoyaltyHubProperties props;
    private final KafkaAdmin kafkaAdmin;

    public LhTopicPartitionGuard(LoyaltyHubProperties props, KafkaAdmin kafkaAdmin) {
        this.props = props;
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public void afterPropertiesSet() {
        if (!props.getTopicSettings().isCreate() || props.getTopicSettings().isAllowPartitionIncrease()) {
            return;
        }
        check(existingPartitions(), props);
    }

    /**
     * Rifiuta un aumento di partizioni non autorizzato. {@code existing}: partizioni attuali per nome di topic (i topic
     * assenti non compaiono: verranno creati con la forma configurata).
     */
    public static void check(Map<String, Integer> existing, LoyaltyHubProperties props) {
        LoyaltyHubProperties.TopicSettings s = props.getTopicSettings();
        if (s.isAllowPartitionIncrease()) {
            return;
        }
        List<String> increases = new ArrayList<>();
        for (String topic : props.getTopics().all()) {
            Integer current = existing.get(topic);
            if (current != null && current < s.getPartitions()) {
                increases.add(topic + " " + current + " → " + s.getPartitions());
            }
        }
        if (!increases.isEmpty()) {
            throw new IllegalStateException("PARTITION_INCREASE_NOT_ACKNOWLEDGED: la configurazione aumenta le partizioni ("
                    + String.join(", ", increases) + "). Aumentare le partizioni rimappa le chiavi memberId e rompe "
                    + "l'ordine per membro degli eventi in volo: fermare i produttori, attendere lag 0 sui consumer, poi "
                    + "riavviare con LH_KAFKA_TOPICS_ALLOW_PARTITION_INCREASE=true "
                    + "(loyaltyhub.topic-settings.allow-partition-increase) e toglierla dopo l'aumento.");
        }
    }

    private Map<String, Integer> existingPartitions() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            Map<String, KafkaFuture<TopicDescription>> described = admin
                    .describeTopics(props.getTopics().all(), new DescribeTopicsOptions().timeoutMs((int) TIMEOUT_MS))
                    .topicNameValues();
            for (Map.Entry<String, KafkaFuture<TopicDescription>> e : described.entrySet()) {
                try {
                    out.put(e.getKey(), e.getValue().get(TIMEOUT_MS, TimeUnit.MILLISECONDS).partitions().size());
                } catch (ExecutionException ex) {
                    if (!(ex.getCause() instanceof UnknownTopicOrPartitionException)) {
                        log.warn("Partizioni di {} non lette ({}): verifica dell'aumento saltata per questo topic",
                                e.getKey(), ex.getCause() == null ? ex : ex.getCause().toString());
                    }
                }
            }
        } catch (TimeoutException | RuntimeException e) {
            log.warn("Broker non raggiungibile per la verifica delle partizioni ({}): la gestisce KafkaAdmin", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }
}
