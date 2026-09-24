package io.loyaltyhub.member.api;

import io.loyaltyhub.member.domain.Segment;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** Forme JSON dell'API dei segmenti (docs/servizi/member-service.md §3; BO-04). */
public final class SegmentViews {

    private SegmentViews() {
    }

    public record SegmentView(String id, String code, String name, String description, String type, JsonNode criteria,
                              String status, int memberCount, Instant refreshedAt, int version, Instant createdAt,
                              Instant updatedAt, String createdBy, String updatedBy) {
        public static SegmentView of(Segment s) {
            return new SegmentView(s.id(), s.code(), s.name(), s.description(), s.type(), s.criteria(), s.status(),
                    s.memberCount(), s.refreshedAt(), s.version(), s.createdAt(), s.updatedAt(), s.createdBy(),
                    s.updatedBy());
        }
    }

    /**
     * Creazione ({@code POST}) e modifica ({@code PUT}, con {@code version}). {@code code} e {@code type} si scelgono
     * alla creazione; {@code memberIds} solo per {@code STATIC} (elenco iniziale); {@code status} per archiviare.
     */
    public record SegmentRequest(String code, String name, String description, String type, JsonNode criteria,
                                 String status, List<String> memberIds, Integer version) {
    }

    public record PreviewRequest(JsonNode criteria) {
    }

    /** Membro campione dell'anteprima e dell'elenco membri di un segmento. */
    public record MemberSample(String memberId, String name, String tier, String status, Instant enteredAt) {
    }

    public record PreviewResult(long count, List<MemberSample> sample) {
    }

    public record RefreshResult(String code, int entered, int left, int total) {
    }

    public record StaticMembersRequest(List<String> memberIds) {
    }

    /** Appartenenza di un membro (BO-03, scheda {@code segments}). */
    public record MemberSegmentView(String id, String code, String name, String type, String status, Instant enteredAt) {
    }

    /** Esito del job demo {@code refresh-segments} (BO-30). */
    public record RefreshJobOutcome(String job, Instant asOf, int segments, int entered, int left) {
    }
}
