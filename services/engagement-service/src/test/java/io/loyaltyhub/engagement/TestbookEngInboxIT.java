package io.loyaltyhub.engagement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class TestbookEngInboxIT {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // Use default embedded kafka props mapped via config if required
    }

    @Test
    @DisplayName("[TB-ENG-IBX-001] Event deduplication")
    void deduplicateEvent() throws Exception {
        String memberId = "MBR-IBX001";
        String eventId = "EVT-" + UUID.randomUUID();

        // 1. Create rule & template
        jdbc.sql("INSERT INTO message_template (code, channel, title_tpl) VALUES ('TPL-IBX', 'INAPP', 'Hello') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO notification_rule (id, fact_type, template_code, enabled) VALUES (?, 'fact.test.ibx', 'TPL-IBX', true)")
            .param(UUID.randomUUID().toString()).update();

        // Ensure member exists
        jdbc.sql("INSERT INTO member_snapshot (member_id, status) VALUES (?, 'ACTIVE') ON CONFLICT DO NOTHING")
            .param(memberId).update();

        publishFact(eventId, "fact.test.ibx", memberId);
        awaitMessage(memberId, eventId);

        // Publish second time
        publishFact(eventId, "fact.test.ibx", memberId);
        Thread.sleep(1000); // Give it some time to potentially fail or ignore

        long count = jdbc.sql("SELECT count(*) FROM inbox_message WHERE source_event_id = ?").param(eventId).query(Long.class).single();
        assertThat(count).isEqualTo(1); // Not duplicated
    }

    @Test
    @DisplayName("[TB-ENG-IBX-002] ANONYMIZED non riceve")
    void anonymizedIgnores() throws Exception {
        String memberId = "MBR-ANON";
        String eventId = "EVT-" + UUID.randomUUID();

        jdbc.sql("INSERT INTO member_snapshot (member_id, status) VALUES (?, 'ANONYMIZED') ON CONFLICT DO NOTHING")
            .param(memberId).update();

        publishFact(eventId, "fact.test.ibx", memberId);
        Thread.sleep(2000); // Wait for processing

        long count = jdbc.sql("SELECT count(*) FROM inbox_message WHERE source_event_id = ?").param(eventId).query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-IBX-003] BLOCKED riceve")
    void blockedReceives() throws Exception {
        String memberId = "MBR-BLOCK";
        String eventId = "EVT-" + UUID.randomUUID();

        jdbc.sql("INSERT INTO member_snapshot (member_id, status) VALUES (?, 'BLOCKED') ON CONFLICT DO NOTHING")
            .param(memberId).update();

        publishFact(eventId, "fact.test.ibx", memberId);
        awaitMessage(memberId, eventId);

        long count = jdbc.sql("SELECT count(*) FROM inbox_message WHERE source_event_id = ?").param(eventId).query(Long.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-IBX-004] Regola disabilitata")
    void disabledRule() throws Exception {
        String memberId = "MBR-DIS";
        String eventId = "EVT-" + UUID.randomUUID();

        jdbc.sql("INSERT INTO notification_rule (id, fact_type, template_code, enabled) VALUES (?, 'fact.test.disabled', 'TPL-IBX', false)")
            .param(UUID.randomUUID().toString()).update();

        publishFact(eventId, "fact.test.disabled", memberId);
        Thread.sleep(2000);

        long count = jdbc.sql("SELECT count(*) FROM inbox_message WHERE source_event_id = ?").param(eventId).query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("[TB-ENG-IBX-005] Unread count")
    void unreadCount() throws Exception {
        String memberId = "MBR-UNREAD";
        String eventId = "EVT-" + UUID.randomUUID();

        jdbc.sql("INSERT INTO member_snapshot (member_id, status) VALUES (?, 'ACTIVE') ON CONFLICT DO NOTHING")
            .param(memberId).update();

        publishFact(eventId, "fact.test.ibx", memberId);
        awaitMessage(memberId, eventId);

        long unread = jdbc.sql("SELECT count(*) FROM inbox_message WHERE member_id = ? AND read_at IS NULL")
                .param(memberId).query(Long.class).single();
        assertThat(unread).isGreaterThanOrEqualTo(1);

        String msgId = jdbc.sql("SELECT id FROM inbox_message WHERE source_event_id = ?")
                .param(eventId).query(String.class).single();

        jdbc.sql("UPDATE inbox_message SET read_at = now() WHERE id = ?").param(msgId).update();

        long unreadAfter = jdbc.sql("SELECT count(*) FROM inbox_message WHERE member_id = ? AND read_at IS NULL")
                .param(memberId).query(Long.class).single();
        assertThat(unreadAfter).isEqualTo(unread - 1);
    }

    private void publishFact(String eventId, String type, String memberId) throws Exception {
        Map<String, Object> evt = Map.of(
                "specversion", "1.0",
                "id", eventId,
                "source", "urn:test",
                "type", type,
                "subject", "member:" + memberId,
                "time", Instant.now().toString(),
                "data", Map.of()
        );
        kafkaTemplate.send("lh.facts.v1", memberId, mapper.writeValueAsString(evt)).get();
    }

    private void awaitMessage(String memberId, String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Long count = jdbc.sql("SELECT count(*) FROM inbox_message WHERE member_id = ? AND source_event_id = ?")
                    .param(memberId).param(eventId).query(Long.class).single();
            if (count != null && count > 0) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Message not arrived for event " + eventId);
    }
}
