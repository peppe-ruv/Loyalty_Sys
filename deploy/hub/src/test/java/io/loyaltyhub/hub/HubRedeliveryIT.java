package io.loyaltyhub.hub;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.hub.bus.HubInProcessBus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HubRedeliveryIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private HubInProcessBus bus;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void redeliverWalletPointsGrantEffectIsIdempotent() throws Exception {
        String memberId = "MBR-000005";

        long before = walletPts(memberId);

        String eventId = "eff-" + UUID.randomUUID().toString();
        String effectId = "grant-" + UUID.randomUUID().toString();
        Map<String, Object> effect = Map.of(
            "specversion", "1.0", "id", eventId, "source", "urn:loyaltyhub:campaign",
            "type", "io.loyaltyhub.effect.points.grant", "subject", "member:" + memberId,
            "time", Instant.now().toString(),
            "data", Map.of("amount", 100, "currency", "PTS", "effectId", effectId)
        );

        String json = mapper.writeValueAsString(effect);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.points.grant".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        long pts = 0;
        while (System.currentTimeMillis() < deadline) {
            pts = walletPts(memberId);
            if (pts > before) {
                break;
            }
            sleep();
        }
        assertThat(pts).isEqualTo(before + 100);

        // Seconda consegna: ricreiamo lo stesso record come se fosse stato riemesso dal broker
        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.points.grant".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(walletPts(memberId)).as("La seconda consegna dello stesso effetto non deve alterare il saldo").isEqualTo(before + 100);
        }
    }

    @Test
    void redeliverMemberRegisteredFactIsIdempotent() throws Exception {
        String memberId = "MBR-000006";

        String eventId = "fact-" + UUID.randomUUID().toString();
        Map<String, Object> fact = Map.of(
            "specversion", "1.0", "id", eventId, "source", "urn:loyaltyhub:member",
            "type", "io.loyaltyhub.fact.member.registered", "subject", "member:" + memberId,
            "time", "2026-09-15T10:01:00Z",
            "data", Map.of("email", "mbr000006@example.com", "memberId", memberId, "status", "ACTIVE")
        );

        String json = mapper.writeValueAsString(fact);

        bus.publish(new ProducerRecord<>("lh.facts.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.fact.member.registered".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        // The first publish shouldn't change the fact that the wallet belongs to MBR-000006
        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).header("X-LH-Actor", "MEMBER:" + memberId).retrieve().body(JsonNode.class);
           assertThat(w.path("memberId").asString()).isEqualTo(memberId);
        }

        // Second publish
        bus.publish(new ProducerRecord<>("lh.facts.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.fact.member.registered".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).header("X-LH-Actor", "MEMBER:" + memberId).retrieve().body(JsonNode.class);
           assertThat(w.path("memberId").asString()).isEqualTo(memberId);
        }
    }

    @Test
    void redeliverCampaignEvaluationOfAnActionIsIdempotent() throws Exception {
        String memberId = "MBR-000007";

        String eventId = "act-" + UUID.randomUUID().toString();
        Map<String, Object> action = Map.of(
            "specversion", "1.0", "id", eventId, "source", "urn:loyaltyhub:source:ecommerce",
            "type", "io.loyaltyhub.action.purchase.completed", "subject", "member:" + memberId,
            "time", "2026-09-15T10:00:00Z",
            "data", Map.of("orderId", "ORD-" + UUID.randomUUID().toString(), "amount", 130, "currency", "EUR", "channel", "ONLINE")
        );

        String json = mapper.writeValueAsString(action);

        long before = walletPts(memberId);

        bus.publish(new ProducerRecord<>("lh.actions.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.action.purchase.completed".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        long pts = 0;
        while (System.currentTimeMillis() < deadline) {
            pts = walletPts(memberId);
            if (pts > before) {
                break;
            }
            sleep();
        }
        long earned = pts - before;
        assertThat(earned).isGreaterThan(0);

        bus.publish(new ProducerRecord<>("lh.actions.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.action.purchase.completed".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(walletPts(memberId)).as("La seconda consegna della stessa azione non deve ricalcolare/attribuire punti multipli").isEqualTo(before + earned);
        }
    }

    @Test
    void redeliverEngagementMessageSendIsIdempotent() throws Exception {
        String memberId = "MBR-000008";

        String id = "EVT-SEND-" + UUID.randomUUID();

        long messagesBefore = getInboxCount(memberId);

        Map<String, Object> effect = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:campaign",
                "type", "io.loyaltyhub.effect.message.send", "subject", "member:" + memberId,
                "time", Instant.now().toString(), "lhcorrelationid", "CORR-" + id, "lhhop", 1,
                "data", Map.of("effectId", "EFF-" + id, "campaignCode", "CMP-BIRTHDAY", "actionId", "ACT-" + id,
                        "actionType", "member.birthday", "templateCode", "MSG-WELCOME", "params", Map.of("firstName", "John")));

        String json = mapper.writeValueAsString(effect);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.message.send".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        long messages = 0;
        while (System.currentTimeMillis() < deadline) {
            messages = getInboxCount(memberId);
            if (messages > messagesBefore) {
                break;
            }
            sleep();
        }
        assertThat(messages).isEqualTo(messagesBefore + 1);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.message.send".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(getInboxCount(memberId)).as("Non deve inviare il messaggio due volte").isEqualTo(messagesBefore + 1);
        }
    }

    @Test
    void redeliverMemberStatsIsIdempotent() throws Exception {
        String memberId = "MBR-000009";
        long start = count("SELECT count(*) FROM member.member_activity_day WHERE member_id = ?", memberId);

        String id = "EVT-" + UUID.randomUUID();
        Map<String, Object> fact = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:service:ingestion",
                "type", "io.loyaltyhub.fact.action.completed", "subject", "member:" + memberId,
                "time", Instant.now().toString(), "lhcorrelationid", id, "lhhop", 1,
                "data", Map.of("actionId", "ACT-" + id, "actionType", "member.login"));
        String json = mapper.writeValueAsString(fact);

        bus.publish(new ProducerRecord<>("lh.facts.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.fact.action.completed".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM member.member_activity_day WHERE member_id = ?", memberId) > start) break;
            sleep();
        }

        bus.publish(new ProducerRecord<>("lh.facts.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.fact.action.completed".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(count("SELECT count(*) FROM member.member_activity_day WHERE member_id = ?", memberId)).isEqualTo(start + 1);
        }
    }

    @Test
    void redeliverWalletSpendEffectIsIdempotent() throws Exception {
        String memberId = "MBR-000001";
        long start = walletPts(memberId);

        String eventId = "eff-" + UUID.randomUUID().toString();
        String effectId = "spend-" + UUID.randomUUID().toString();
        Map<String, Object> effect = Map.of(
            "specversion", "1.0", "id", eventId, "source", "urn:loyaltyhub:reward",
            "type", "io.loyaltyhub.effect.points.spend", "subject", "member:" + memberId,
            "time", Instant.now().toString(),
            "data", Map.of("amount", 50, "currency", "PTS", "effectId", effectId)
        );
        String json = mapper.writeValueAsString(effect);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.points.spend".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (walletPts(memberId) < start) break;
            sleep();
        }
        assertThat(walletPts(memberId)).isEqualTo(start - 50);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.points.spend".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(walletPts(memberId)).isEqualTo(start - 50);
        }
    }

    @Test
    void redeliverGamificationPlaysGrantIsIdempotent() throws Exception {
        String memberId = "MBR-000002";
        long start = count("SELECT count(*) FROM gamification.play WHERE member_id = ?", memberId);

        String id = "eff-" + UUID.randomUUID().toString();
        Map<String, Object> effect = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:campaign",
                "type", "io.loyaltyhub.effect.plays.grant", "subject", "member:" + memberId,
                "time", Instant.now().toString(),
                "data", Map.of("effectId", "EFF-" + id, "contestCode", "WHEEL", "plays", 1));
        String json = mapper.writeValueAsString(effect);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.plays.grant".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM gamification.play WHERE member_id = ?", memberId) > start) break;
            sleep();
        }

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.plays.grant".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(count("SELECT count(*) FROM gamification.play WHERE member_id = ?", memberId)).isEqualTo(start + 1);
        }
    }

    @Test
    void redeliverRewardCouponIssueIsIdempotent() throws Exception {
        String memberId = "MBR-000003";
        long start = count("SELECT count(*) FROM reward.coupon WHERE member_id = ?", memberId);

        String id = "eff-" + UUID.randomUUID().toString();
        Map<String, Object> effect = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:campaign",
                "type", "io.loyaltyhub.effect.coupon.issue", "subject", "member:" + memberId,
                "time", Instant.now().toString(),
                "data", Map.of("effectId", "EFF-" + id, "poolCode", "COFFEE"));
        String json = mapper.writeValueAsString(effect);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.coupon.issue".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM reward.coupon WHERE member_id = ?", memberId) > start) break;
            sleep();
        }

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.coupon.issue".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(count("SELECT count(*) FROM reward.coupon WHERE member_id = ?", memberId)).isEqualTo(start + 1);
        }
    }

    @Test
    void redeliverGamificationAwardBadgeIsIdempotent() throws Exception {
        String memberId = "MBR-000010";
        long start = count("SELECT count(*) FROM gamification.member_badge WHERE member_id = ?", memberId);

        String id = "eff-" + UUID.randomUUID().toString();
        Map<String, Object> effect = Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:campaign",
                "type", "io.loyaltyhub.effect.badge.award", "subject", "member:" + memberId,
                "time", Instant.now().toString(),
                "data", Map.of("effectId", "EFF-" + id, "badgeCode", "COFFEE_LOVER"));
        String json = mapper.writeValueAsString(effect);

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.badge.award".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM gamification.member_badge WHERE member_id = ?", memberId) > start) break;
            sleep();
        }

        bus.publish(new ProducerRecord<>("lh.effects.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.effect.badge.award".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, id.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(count("SELECT count(*) FROM gamification.member_badge WHERE member_id = ?", memberId)).isEqualTo(start + 1);
        }
    }

    @Test
    void redeliverFactMemberUpdatedIsIdempotent() throws Exception {
        String memberId = "MBR-000008";

        String eventId = "fact-" + UUID.randomUUID().toString();
        Map<String, Object> fact = Map.of(
            "specversion", "1.0", "id", eventId, "source", "urn:loyaltyhub:member",
            "type", "io.loyaltyhub.fact.member.updated", "subject", "member:" + memberId,
            "time", "2026-09-15T10:01:00Z",
            "data", Map.of("email", "new_email@example.com", "firstName", "Test")
        );

        String json = mapper.writeValueAsString(fact);

        bus.publish(new ProducerRecord<>("lh.facts.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.fact.member.updated".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).header("X-LH-Actor", "MEMBER:" + memberId).retrieve().body(JsonNode.class);
           assertThat(w.path("memberId").asString()).isEqualTo(memberId);
        }

        bus.publish(new ProducerRecord<>("lh.facts.v1", null, memberId, json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.fact.member.updated".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).header("X-LH-Actor", "MEMBER:" + memberId).retrieve().body(JsonNode.class);
           assertThat(w.path("memberId").asString()).isEqualTo(memberId);
        }
    }

    @Test
    void redeliverInsightAuditEntryIsIdempotent() throws Exception {
        long start = count("SELECT count(*) FROM insight.audit_entry");

        String eventId = "audit-" + UUID.randomUUID().toString();
        Map<String, Object> fact = Map.of(
            "specversion", "1.0", "id", eventId, "source", "urn:loyaltyhub:campaign",
            "type", "io.loyaltyhub.audit.entry", "subject", "campaign:CAMP-1",
            "time", Instant.now().toString(),
            "data", Map.of("level", "INFO", "msgKey", "TEST", "msg", "Test")
        );

        String json = mapper.writeValueAsString(fact);

        bus.publish(new ProducerRecord<>("lh.audit.v1", null, "campaign:CAMP-1", json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.audit.entry".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM insight.audit_entry") > start) break;
            sleep();
        }

        bus.publish(new ProducerRecord<>("lh.audit.v1", null, "campaign:CAMP-1", json,
                java.util.List.of(
                    new RecordHeader(LhHeaders.TYPE, "io.loyaltyhub.audit.entry".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.ACTOR, "ADMIN".getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(LhHeaders.CORRELATION_ID, eventId.getBytes(StandardCharsets.UTF_8))
                )));

        long steadyDeadline = System.currentTimeMillis() + 3000;
        while(System.currentTimeMillis() < steadyDeadline) {
           sleep(500);
           assertThat(count("SELECT count(*) FROM insight.audit_entry")).isEqualTo(start + 1);
        }
    }

    @Test
// removed test
    // helper

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long count(String sql, String memberId) {
        return jdbc.sql(sql).params(memberId).query(Long.class).single();
    }

    private long walletPts(String memberId) {
        JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId).header("X-LH-Actor", "MEMBER:" + memberId).retrieve().body(JsonNode.class);
        return w.path("balances").path("PTS").path("active").asLong();
    }

    private long getInboxCount(String memberId) {
        try {
            JsonNode res = client().get().uri("/v1/messages?size=100&memberId=" + memberId).header("X-LH-Actor", ADMIN).retrieve().body(JsonNode.class);
            if (res == null) return 0;
            if (res.has("totalElements")) return res.path("totalElements").asLong(0);
            if (res.has("items")) {
                long c = 0;
                for (JsonNode it : res.path("items")) {
                    c++;
                }
                return c;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep() {
        sleep(500);
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
