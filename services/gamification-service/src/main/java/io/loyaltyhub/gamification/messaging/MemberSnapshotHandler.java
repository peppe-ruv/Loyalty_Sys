package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.gamification.infra.MemberSnapshotRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Snapshot del membro per il gioco (docs/servizi/gamification-service.md §4): nickname e stato dai fatti di member. */
@Component
public class MemberSnapshotHandler implements EventHandler {

    private final MemberSnapshotRepository members;

    public MemberSnapshotHandler(MemberSnapshotRepository members) {
        this.members = members;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Fact.MEMBER_REGISTERED, LhEventTypes.Fact.MEMBER_UPDATED, LhEventTypes.Fact.MEMBER_STATUS_CHANGED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        JsonNode d = event.data();
        if (memberId == null || d == null) {
            return;
        }
        if (LhEventTypes.Fact.MEMBER_STATUS_CHANGED.equals(event.type())) {
            if (d.hasNonNull("newStatus")) {
                members.updateStatus(memberId, d.get("newStatus").asString());
            }
            return;
        }
        members.upsert(memberId, nickname(d), d.path("status").asString("ACTIVE"));
    }

    /** Nickname del membro; se manca, nome + iniziale del cognome (docs/03 §8). */
    static String nickname(JsonNode d) {
        if (d.hasNonNull("nickname") && !d.get("nickname").asString().isBlank()) {
            return d.get("nickname").asString();
        }
        String first = d.path("firstName").asString("");
        String last = d.path("lastName").asString("");
        if (first.isBlank()) {
            return null;
        }
        return last.isBlank() ? first : first + " " + last.charAt(0) + ".";
    }
}
