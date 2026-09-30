package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.privacy.PersonalData;
import io.loyaltyhub.gamification.application.MemberSubjectProjection;
import io.loyaltyhub.gamification.infra.MemberErasureRepository;
import io.loyaltyhub.gamification.infra.MemberSnapshotRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Snapshot del membro per il gioco (docs/servizi/gamification-service.md §4): nickname e stato dai fatti di member.
 * Doppia lettura {@code member.*:1}/{@code :2} ({@link MemberSnapshotFact}, ADR-032, Q-346): da {@code :2} arriva solo
 * lo stato; un campo assente non sovrascrive quello salvato. Il nome delle classifiche lo risolve il BFF (Q-368).
 * Nella stessa transazione (quella idempotente dell'inbox) proietta il legame {@code subjectRef → membro} nello stesso
 * snapshot ({@link MemberSubjectProjection}, F2-SEC-09, ADR-048, Q-550): il membro del token si risolve con un indice locale.
 */
@Component
public class MemberSnapshotHandler implements EventHandler {

    private final MemberSnapshotRepository members;
    private final MemberErasureRepository erasure;
    private final MemberSubjectProjection subjects;

    public MemberSnapshotHandler(MemberSnapshotRepository members, MemberErasureRepository erasure,
                                 MemberSubjectProjection subjects) {
        this.members = members;
        this.erasure = erasure;
        this.subjects = subjects;
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
        snapshot(memberId, d, event);
        // Legame account↔membro (Q-550): dopo lo snapshot, nella stessa transazione; l'anonimizzazione scrive la lapide.
        subjects.apply(memberId, event);
    }

    private void snapshot(String memberId, JsonNode d, LhEvent<JsonNode> event) {
        // Anonimizzazione (F-MBR-05, M7.5): segnaposto al posto del nickname, qualunque dei due fatti arrivi prima.
        if (PersonalData.isAnonymization(event)) {
            erasure.erase(memberId);
            return;
        }
        if (LhEventTypes.Fact.MEMBER_STATUS_CHANGED.equals(event.type())) {
            if (d.hasNonNull("newStatus")) {
                members.updateStatus(memberId, d.get("newStatus").asString());
            }
            return;
        }
        MemberSnapshotFact fact = MemberSnapshotFact.parse(event.dataschema(), d);
        members.upsert(memberId, fact.nickname(), fact.status());
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
