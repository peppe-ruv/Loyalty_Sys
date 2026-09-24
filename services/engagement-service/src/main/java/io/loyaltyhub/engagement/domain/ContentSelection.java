package io.loyaltyhub.engagement.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Selezione dei contenuti per posizionamento (docs/03 §9, F-CNT-01/04): {@code LIVE} ∧ in calendario ∧ pubblico
 * soddisfatto, per {@code priority} decrescente (a parità, per codice); {@code HOME_HERO} ne mostra 1, {@code HOME_GRID}
 * fino a 6. Per l'anteprima di BO-18 ogni escluso ha il motivo: il primo che fallisce tra stato, calendario, pubblico.
 * Logica pura: nessun accesso a database od orologio.
 */
public final class ContentSelection {

    // SPEC-GAP: Q-72 — docs/03 §9 fissa solo HOME_HERO 1 e HOME_GRID 6; gli altri limiti sono scelti qui.
    public static final Map<String, Integer> LIMITS = Map.of(
            "HOME_HERO", 1, "HOME_GRID", 6, "CATALOG_TOP", 1, "CONTEST", 3, "WIN", 1);

    /** Chi guarda: livello, segmenti e stato dallo snapshot (null = membro sconosciuto). */
    public record Viewer(String tier, List<String> segments, String status) {
        public static final Viewer UNKNOWN = new Viewer(null, List.of(), null);
    }

    public record Excluded(ContentItem item, String reason) {
    }

    public record Result(List<ContentItem> shown, List<Excluded> excluded) {
    }

    private ContentSelection() {
    }

    public static Result select(List<ContentItem> candidates, Viewer viewer, Instant now, int limit) {
        List<ContentItem> eligible = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (ContentItem c : candidates) {
            String reason = exclusion(c, viewer, now);
            if (reason == null) {
                eligible.add(c);
            } else {
                excluded.add(new Excluded(c, reason));
            }
        }
        eligible.sort(Comparator.comparingInt(ContentItem::priority).reversed().thenComparing(ContentItem::code));
        return new Result(eligible.subList(0, Math.min(limit, eligible.size())), excluded);
    }

    /** Motivo di esclusione ({@code NOT_LIVE, OUT_OF_SCHEDULE, NOT_IN_AUDIENCE}) o {@code null} se visibile. */
    public static String exclusion(ContentItem c, Viewer viewer, Instant now) {
        if (!"LIVE".equals(c.status())) {
            return "NOT_LIVE";
        }
        if (!inSchedule(c, now)) {
            return "OUT_OF_SCHEDULE";
        }
        if (!inAudience(c.audience(), viewer)) {
            return "NOT_IN_AUDIENCE";
        }
        return null;
    }

    public static boolean inSchedule(ContentItem c, Instant now) {
        return (c.startAt() == null || !c.startAt().isAfter(now)) && (c.endAt() == null || c.endAt().isAfter(now));
    }

    /** Ogni dimensione non vuota deve essere soddisfatta (livelli, segmenti: almeno uno in comune, stati). */
    public static boolean inAudience(JsonNode audience, Viewer viewer) {
        if (audience == null || audience.isNull() || audience.isEmpty()) {
            return true;
        }
        List<String> tiers = strings(audience.get("tiers"));
        List<String> segments = strings(audience.get("segments"));
        List<String> statuses = strings(audience.get("statuses"));
        if (!tiers.isEmpty() && (viewer.tier() == null || !tiers.contains(viewer.tier()))) {
            return false;
        }
        if (!segments.isEmpty() && viewer.segments().stream().noneMatch(segments::contains)) {
            return false;
        }
        return statuses.isEmpty() || (viewer.status() != null && statuses.contains(viewer.status()));
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asString()));
        }
        return out;
    }
}
