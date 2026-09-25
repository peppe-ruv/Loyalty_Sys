package io.loyaltyhub.engagement.domain;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /** Chi guarda: livello, segmenti, stato e iscrizione dallo snapshot (null = membro sconosciuto). */
    public record Viewer(String tier, List<String> segments, String status, Instant registeredAt) {
        public static final Viewer UNKNOWN = new Viewer(null, List.of(), null, null);
    }

    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

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
        if (!inAudience(c.audience(), viewer, now)) {
            return "NOT_IN_AUDIENCE";
        }
        return null;
    }

    /**
     * Pop-up (docs/03 §9): al più uno per visita, il primo per priorità che passa stato, calendario, pubblico e
     * frequenza ({@code ONCE}: mai visto; {@code ONCE_PER_DAY}: non visto oggi; {@code ALWAYS}: sempre).
     * {@code lastSeen} = ultimo giorno di vista per id del pop-up.
     */
    public static Result selectPopup(List<ContentItem> popups, Viewer viewer, Instant now, Map<String, LocalDate> lastSeen) {
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        List<ContentItem> eligible = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (ContentItem c : popups) {
            String reason = exclusion(c, viewer, now);
            if (reason == null && !frequencyAllows(c.frequency(), lastSeen.get(c.id()), today)) {
                reason = "FREQUENCY";
            }
            if (reason == null) {
                eligible.add(c);
            } else {
                excluded.add(new Excluded(c, reason));
            }
        }
        eligible.sort(Comparator.comparingInt(ContentItem::priority).reversed().thenComparing(ContentItem::code));
        return new Result(eligible.subList(0, Math.min(1, eligible.size())), excluded);
    }

    public static boolean frequencyAllows(String frequency, LocalDate lastSeen, LocalDate today) {
        if (lastSeen == null || "ALWAYS".equals(frequency)) {
            return true;
        }
        return "ONCE_PER_DAY".equals(frequency) && lastSeen.isBefore(today);
    }

    public static boolean inSchedule(ContentItem c, Instant now) {
        return (c.startAt() == null || !c.startAt().isAfter(now)) && (c.endAt() == null || c.endAt().isAfter(now));
    }

    /**
     * Ogni dimensione non vuota deve essere soddisfatta (livelli, segmenti: almeno uno in comune, stati). Estensioni dei
     * pop-up del seed (SPEC-GAP Q-71): {@code registeredWithinDays} (iscrizione nota e da meno di N giorni, «iscritti da
     * &lt; 7 giorni» di docs/10 §7: a N giorni esatti il membro è fuori) e {@code daysOfWeek} ({@code MON…SUN}, giorno di
     * oggi in Europe/Rome).
     */
    public static boolean inAudience(JsonNode audience, Viewer viewer, Instant now) {
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
        if (!statuses.isEmpty() && (viewer.status() == null || !statuses.contains(viewer.status()))) {
            return false;
        }
        JsonNode within = audience.get("registeredWithinDays");
        if (within != null && within.isNumber()
                && (viewer.registeredAt() == null || !viewer.registeredAt().isAfter(now.minus(Duration.ofDays(within.asInt()))))) {
            return false;
        }
        List<String> days = strings(audience.get("daysOfWeek"));
        String today = LocalDate.ofInstant(now, ZONE).getDayOfWeek().name().substring(0, 3);
        return days.isEmpty() || days.contains(today);
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asString()));
        }
        return out;
    }
}
