package io.loyaltyhub.campaign.messaging;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes.Fact;
import io.loyaltyhub.common.inbox.EventHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * Mantiene {@code member_snapshot} dai fatti member, {@code member.segment.*} e tier (docs/servizi/campaign-service.md §4): il motore
 * non fa chiamate sincrone, valuta sullo snapshot locale. {@code registered}/{@code updated} portano lo snapshot completo.
 */
@Component
public class MemberSnapshotHandler implements EventHandler {

    private final MemberSnapshotRepository snapshots;

    public MemberSnapshotHandler(MemberSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(Fact.MEMBER_REGISTERED, Fact.MEMBER_UPDATED, Fact.MEMBER_STATUS_CHANGED,
                Fact.TIER_UPGRADED, Fact.TIER_DOWNGRADED, Fact.MEMBER_SEGMENT_ENTERED, Fact.MEMBER_SEGMENT_LEFT);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        if (memberId == null) {
            return;
        }
        JsonNode d = event.data();
        switch (event.type()) {
            case Fact.MEMBER_REGISTERED, Fact.MEMBER_UPDATED -> {
                snapshots.upsertIdentity(
                        memberId, text(d, "status", "ACTIVE"), text(d, "tier", null),
                        instant(d, "registeredAt"), date(d, "birthDate"),
                        d != null && d.has("attributes") ? d.get("attributes").toString() : "{}");
                // Lo snapshot completo porta le etichette (EVT-FACT-01/02): servono a `member.labels` (M6.6).
                if (d != null && d.has("labels") && d.get("labels").isArray()) {
                    java.util.List<String> labels = new java.util.ArrayList<>();
                    d.get("labels").forEach(l -> labels.add(l.asString()));
                    snapshots.updateLabels(memberId, labels);
                }
            }
            // Pubblico per segmento delle campagne (F-CMP-06, M6.6): i segmenti arrivano solo dai fatti di member.
            case Fact.MEMBER_SEGMENT_ENTERED -> {
                String code = text(d, "segmentCode", null);
                if (code != null) {
                    snapshots.addSegment(memberId, code);
                }
            }
            case Fact.MEMBER_SEGMENT_LEFT -> {
                String code = text(d, "segmentCode", null);
                if (code != null) {
                    snapshots.removeSegment(memberId, code);
                }
            }
            case Fact.MEMBER_STATUS_CHANGED -> snapshots.updateStatus(memberId, text(d, "newStatus", "ACTIVE"));
            case Fact.TIER_UPGRADED, Fact.TIER_DOWNGRADED -> {
                String tier = text(d, "newTier", null);
                if (tier != null) {
                    snapshots.updateTier(memberId, tier);
                }
            }
            default -> {
            }
        }
    }

    private static String text(JsonNode d, String field, String def) {
        if (d == null || !d.has(field) || d.get(field).isNull()) {
            return def;
        }
        return d.get(field).asString(def);
    }

    private static Instant instant(JsonNode d, String field) {
        String v = text(d, field, null);
        try {
            return v == null ? null : Instant.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate date(JsonNode d, String field) {
        String v = text(d, field, null);
        try {
            return v == null ? null : LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }
}
