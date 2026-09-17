package io.loyaltyhub.memberservice.app;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Anniversari di adesione (azione MEMBERSHIP_ANNIVERSARY, una per anno) e aggancio alle azioni per FIRST_ACTION/referral. */
@Component
public class MemberJobs {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final MemberService members;

    public MemberJobs(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, MemberService members) {
        this.jdbc = jdbc; this.kafka = kafka; this.members = members;
    }

    @Scheduled(cron = "${members.anniversary-cron:0 15 4 * * *}")
    public void anniversaries() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Rome"));
        Instant now = Instant.now();
        jdbc.query("SELECT id, enrolled_at FROM memberservice.member WHERE status = 'ACTIVE' AND extract(month FROM enrolled_at AT TIME ZONE 'Europe/Rome') = ? AND extract(day FROM enrolled_at AT TIME ZONE 'Europe/Rome') = ? AND enrolled_at < now() - interval '360 days'",
                rs -> {
                    String id = rs.getString(1);
                    int years = today.getYear() - rs.getTimestamp(2).toInstant().atZone(ZoneId.of("Europe/Rome")).getYear();
                    var a = new RewardingAction(EventTypes.ACTION_ANNIVERSARY, "member:" + id + ":ANNIVERSARY:" + today.getYear(), null, now, null, Map.of("years", years));
                    kafka.send(EventTypes.TOPIC_ACTIONS, id, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.ACTION_V1, "urn:loyaltyhub:members", "member:" + id, a)));
                }, today.getMonthValue(), today.getDayOfMonth());
    }

    @KafkaListener(topics = EventTypes.TOPIC_ACTIONS, groupId = "member-service")
    public void onAction(byte[] payload) {
        var event = CanonicalEvents.deserialize(payload);
        if (!EventTypes.ACTION_V1.equals(event.getType())) return;
        var action = CanonicalEvents.data(event, RewardingAction.class);
        if (action.isReversal() || EventTypes.ACTION_FIRST_ACTION.equals(action.actionType())) return;
        members.onAction(CanonicalEvents.memberId(event), action.actionType(), action.occurredAt() == null ? Instant.now() : action.occurredAt());
    }
}
