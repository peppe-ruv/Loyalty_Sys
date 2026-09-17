package it.iren.loyalty.engagementservice.app;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Proietta dal topic membri il presentatore (milestone REFERRAL) e le etichette usate come gruppo di classifica. */
@Component
public class MemberProjection {
    private final JdbcTemplate jdbc;
    public MemberProjection(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @KafkaListener(topics = EventTypes.TOPIC_MEMBERS, groupId = "engagement-service-members")
    public void onMember(byte[] payload) {
        var event = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(event, Map.class);
        String id = String.valueOf(m.get("id"));
        if (m.get("referredBy") != null) jdbc.update("INSERT INTO engagementservice.member_referrer(member_id, referrer_id) VALUES (?,?) ON CONFLICT (member_id) DO UPDATE SET referrer_id = EXCLUDED.referrer_id", id, String.valueOf(m.get("referredBy")));
        if (m.get("labels") instanceof Map<?, ?> labels) labels.forEach((k, v) ->
                jdbc.update("INSERT INTO engagementservice.member_group(member_id, group_key, group_value) VALUES (?,?,?) ON CONFLICT (member_id, group_key) DO UPDATE SET group_value = EXCLUDED.group_value", id, String.valueOf(k), String.valueOf(v)));
    }
}
