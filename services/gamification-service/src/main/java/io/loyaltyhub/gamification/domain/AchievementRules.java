package io.loyaltyhub.gamification.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;

/**
 * Regole pure degli obiettivi (docs/03 §8): chiave di periodo, filtro sui dati dell'azione e avanzamento per metrica.
 * Senza stato né Spring: il servizio legge il progresso, chiama {@link #advance} e salva.
 */
public final class AchievementRules {

    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    /** Progresso di un membro su un obiettivo in un periodo. */
    public record Progress(long value, List<String> distinctSeen, String lastUnitKey) {
        public static final Progress EMPTY = new Progress(0, List.of(), null);
    }

    private AchievementRules() {
    }

    /**
     * {@code EVER} → {@code EVER}; {@code MONTH} → {@code 2026-09}; {@code EDITION} → {@code ED-2026}. Le edizioni del
     * seed coincidono con l'anno solare e sono di wallet-service: qui si ricava dal giorno dell'azione (SPEC-GAP Q-59).
     */
    // SPEC-GAP: Q-59
    public static String periodKey(String period, Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return switch (period) {
            case "MONTH" -> String.format("%d-%02d", d.getYear(), d.getMonthValue());
            case "EDITION" -> "ED-" + d.getYear();
            default -> "EVER";
        };
    }

    /** Unità della serie: giorno ({@code 2026-09-24}) o settimana ISO ({@code 2026-W39}). */
    public static String unitKey(String unit, Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return "WEEK".equals(unit) ? weekKey(d) : d.toString();
    }

    private static String previousUnitKey(String unit, Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return "WEEK".equals(unit) ? weekKey(d.minusWeeks(1)) : d.minusDays(1).toString();
    }

    private static String weekKey(LocalDate d) {
        return String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /**
     * Filtro opzionale sui dati dell'azione: {@code {"op":"all","rules":[{"field":"data.amount","cmp":"gte","value":50}]}}
     * con {@code eq, neq, gt, gte, lt, lte, in}. Nessun filtro → passa. Campo assente o {@code null} → foglia falsa.
     */
    public static boolean matches(JsonNode filter, JsonNode data) {
        if (filter == null || filter.isNull() || !filter.has("rules")) {
            return true;
        }
        boolean any = "any".equals(filter.path("op").asString("all"));
        boolean result = !any;
        for (JsonNode r : filter.path("rules")) {
            boolean ok = rule(r, field(data, r.path("field").asString("")));
            result = any ? result || ok : result && ok;
        }
        return result;
    }

    /**
     * Avanza il progresso per un'azione: {@code COUNT} +1, {@code SUM} + campo, {@code DISTINCT_TYPES} tipi distinti
     * tra quelli osservati, {@code STREAK} unità consecutive (stessa unità → invariato; un buco riparte da 1).
     */
    public static Progress advance(Achievement a, Progress p, String actionType, JsonNode data, Instant at) {
        return switch (a.metric()) {
            case "SUM" -> {
                JsonNode v = field(data, a.sumField() == null ? "" : a.sumField());
                long add = v == null || !v.isNumber() ? 0 : v.decimalValue().setScale(0, java.math.RoundingMode.DOWN).longValue();
                yield add <= 0 ? p : new Progress(p.value() + add, p.distinctSeen(), p.lastUnitKey());
            }
            case "DISTINCT_TYPES" -> {
                if (p.distinctSeen().contains(actionType)) {
                    yield p;
                }
                List<String> seen = new ArrayList<>(p.distinctSeen());
                seen.add(actionType);
                yield new Progress(seen.size(), List.copyOf(seen), p.lastUnitKey());
            }
            case "STREAK" -> {
                String unit = a.streakUnit() == null ? "DAY" : a.streakUnit();
                String key = unitKey(unit, at);
                if (key.equals(p.lastUnitKey())) {
                    yield p;
                }
                long value = previousUnitKey(unit, at).equals(p.lastUnitKey()) ? p.value() + 1 : 1;
                yield new Progress(value, p.distinctSeen(), key);
            }
            default -> new Progress(p.value() + 1, p.distinctSeen(), p.lastUnitKey());
        };
    }

    static JsonNode field(JsonNode data, String path) {
        if (data == null || path.isBlank()) {
            return null;
        }
        String clean = path.startsWith("data.") ? path.substring(5) : path;
        int dot = clean.lastIndexOf("data.");
        if (dot > 0) {
            clean = clean.substring(dot + 5); // es. "purchase.completed.data.amount" (docs/10) → "amount"
        }
        JsonNode node = data;
        for (String seg : clean.split("\\.")) {
            node = node == null ? null : node.get(seg);
        }
        return node;
    }

    private static boolean rule(JsonNode r, JsonNode actual) {
        JsonNode expected = r.path("value");
        String cmp = r.path("cmp").asString("eq");
        if (actual == null || actual.isNull()) {
            // docs/03 §3.3: campo assente → foglia falsa con ogni comparatore (il filtro non ha nexists), come nel motore campagne.
            return false;
        }
        if ("in".equals(cmp)) {
            for (JsonNode e : expected) {
                if (e.asString("").equals(actual.asString(""))) {
                    return true;
                }
            }
            return false;
        }
        if (actual.isNumber() && expected.isNumber()) {
            int c = actual.decimalValue().compareTo(expected.decimalValue());
            return switch (cmp) {
                case "neq" -> c != 0;
                case "gt" -> c > 0;
                case "gte" -> c >= 0;
                case "lt" -> c < 0;
                case "lte" -> c <= 0;
                default -> c == 0;
            };
        }
        boolean eq = actual.asString("").equals(expected.asString(""));
        return "neq".equals(cmp) ? !eq : "eq".equals(cmp) && eq;
    }

}
