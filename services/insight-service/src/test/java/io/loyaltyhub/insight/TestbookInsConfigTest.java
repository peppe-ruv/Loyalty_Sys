package io.loyaltyhub.insight;

import io.loyaltyhub.insight.application.RetentionJob;
import io.loyaltyhub.insight.messaging.TopicListeners;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — configurazione (docs/testbook/TB-INS-insight.md §11): soglie di conservazione, frequenza del job
 * e gruppo consumer. Oracolo: insight §4 («tutti e 5, gruppo lh-insight»), §5 (RNF-07: 14 giorni o 200 000 righe,
 * payload 8 KB, audit 180 giorni, job orario).
 */
class TestbookInsConfigTest {

    private static Properties yaml() {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource("application.yml"));
        return y.getObject();
    }

    private static String defaultOf(String key) throws Exception {
        for (var c : RetentionJob.class.getConstructors()) {
            for (Parameter p : c.getParameters()) {
                Value v = p.getAnnotation(Value.class);
                if (v != null && v.value().contains(key)) {
                    return v.value().substring(v.value().indexOf(':') + 1, v.value().length() - 1);
                }
            }
        }
        throw new AssertionError("nessun @Value per " + key);
    }

    @Test
    @DisplayName("[TB-INS-CFG-001] event store: 14 giorni")
    void maxAge() throws Exception {
        assertThat(yaml().getProperty("loyaltyhub.insight.retention.max-age-days")).isEqualTo("14");
        assertThat(defaultOf("max-age-days")).isEqualTo("14");
    }

    @Test
    @DisplayName("[TB-INS-CFG-002] event store: 200 000 righe")
    void maxRows() throws Exception {
        assertThat(yaml().getProperty("loyaltyhub.insight.retention.max-rows")).isEqualTo("200000");
        assertThat(defaultOf("max-rows")).isEqualTo("200000");
    }

    @Test
    @DisplayName("[TB-INS-CFG-003] audit: 180 giorni")
    void auditAge() throws Exception {
        assertThat(defaultOf("audit-max-age-days")).isEqualTo("180");
    }

    @Test
    @DisplayName("[TB-INS-CFG-004] payload troncato a 8 KB: soglia 8192 byte")
    void payloadBytes() {
        assertThat(yaml().getProperty("loyaltyhub.insight.retention.payload-max-bytes")).isEqualTo("8192");
    }

    @Test
    @DisplayName("[TB-INS-CFG-005] job di conservazione orario: 24 esecuzioni al giorno, una all'ora")
    void hourly() throws Exception {
        Method purge = RetentionJob.class.getMethod("purge");
        String annotated = purge.getAnnotation(Scheduled.class).cron();
        String cron = yaml().getProperty("loyaltyhub.insight.retention.cron");
        assertThat(annotated).endsWith(":" + cron + "}");
        CronExpression expr = CronExpression.parse(cron);
        LocalDateTime t = LocalDateTime.of(2026, 3, 29, 0, 0);
        List<LocalDateTime> runs = new ArrayList<>();
        for (LocalDateTime next = expr.next(t); next.isBefore(t.plusDays(1)); next = expr.next(next)) {
            runs.add(next);
        }
        assertThat(runs).hasSize(24);
    }

    @Test
    @DisplayName("[TB-INS-CFG-006] consumer: tutti e 5 i topic col gruppo lh-insight")
    void listeners() {
        List<String> topics = new ArrayList<>();
        for (Method m : TopicListeners.class.getMethods()) {
            KafkaListener l = m.getAnnotation(KafkaListener.class);
            if (l != null) {
                assertThat(l.groupId()).isEqualTo("lh-insight");
                for (String t : l.topics()) {
                    topics.add(t.substring(t.indexOf(':') + 1, t.length() - 1));
                }
            }
        }
        assertThat(topics).containsExactlyInAnyOrder("lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1",
                "lh.dlq.v1");
    }
}
