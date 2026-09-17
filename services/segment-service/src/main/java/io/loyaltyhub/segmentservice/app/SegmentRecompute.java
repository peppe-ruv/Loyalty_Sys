package io.loyaltyhub.segmentservice.app;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.segmentservice.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ricalcolo delle appartenenze: completo su pianificazione, puntuale quando arriva un evento del membro.
 * Ingresso e uscita da un segmento producono l'evento SEGMENT_MEMBERSHIP (per CRM/marketing) e, all'ingresso,
 * l'azione interna SEGMENT_ENTERED (ADR-008), idempotente per membro+segmento+giorno.
 */
@Component
public class SegmentRecompute {
    private static final Logger log = LoggerFactory.getLogger(SegmentRecompute.class);
    private final SegmentSource segments;
    private final SnapshotSource snapshots;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final SegmentEvaluator evaluator = new SegmentEvaluator();

    public SegmentRecompute(SegmentSource segments, SnapshotSource snapshots, JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka) {
        this.segments = segments; this.snapshots = snapshots; this.jdbc = jdbc; this.kafka = kafka;
    }

    @Scheduled(cron = "${segments.recompute-cron:0 0 3 * * *}")
    public void recomputeAll() {
        List<Segment> defs = segments.publishedSegments().stream().filter(Segment::active).toList();
        Instant now = Instant.now();
        long[] n = {0};
        try (var members = snapshots.allActive()) {
            members.forEach(m -> { recompute(m, defs, now); n[0]++; });
        }
        log.info("segments recomputed for {} members, {} segments", n[0], defs.size());
    }

    public void recomputeMember(String memberId) {
        snapshots.snapshotOf(memberId).ifPresent(m -> recompute(m, segments.publishedSegments().stream().filter(Segment::active).toList(), Instant.now()));
    }

    @Transactional
    public void recompute(MemberSnapshot m, List<Segment> defs, Instant now) {
        Set<String> before = new HashSet<>(jdbc.queryForList("SELECT segment_id FROM segmentservice.membership WHERE member_id = ?", String.class, m.memberId()));
        Set<String> after = new HashSet<>();
        for (Segment s : defs) if (evaluator.matches(s, m, now)) after.add(s.id());
        for (String id : after) if (!before.contains(id)) {
            jdbc.update("INSERT INTO segmentservice.membership(member_id, segment_id, entered_at) VALUES (?,?,?) ON CONFLICT DO NOTHING", m.memberId(), id, java.sql.Timestamp.from(now));
            emit(m.memberId(), id, true, now);
        }
        for (String id : before) if (!after.contains(id)) {
            jdbc.update("DELETE FROM segmentservice.membership WHERE member_id = ? AND segment_id = ?", m.memberId(), id);
            emit(m.memberId(), id, false, now);
        }
    }

    private void emit(String memberId, String segmentId, boolean entered, Instant now) {
        var membership = CanonicalEvents.of(EventTypes.SEGMENT_MEMBERSHIP_V1, "urn:loyaltyhub:segments", "member:" + memberId,
                Map.of("segmentId", segmentId, "entered", entered, "at", now.toString()));
        kafka.send(EventTypes.TOPIC_SEGMENTS, memberId, CanonicalEvents.serialize(membership));
        if (entered) {
            String day = now.toString().substring(0, 10);
            var action = new RewardingAction(EventTypes.ACTION_SEGMENT_ENTERED, "segments:" + memberId + ":" + segmentId + ":" + day, segmentId, now, null, Map.of("segmentId", segmentId));
            var event = CanonicalEvents.of(EventTypes.ACTION_V1, "urn:loyaltyhub:segments", "member:" + memberId, action);
            kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(event));
        }
    }
}
