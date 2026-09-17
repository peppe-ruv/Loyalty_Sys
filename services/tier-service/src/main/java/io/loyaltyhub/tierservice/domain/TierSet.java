package io.loyaltyhub.tierservice.domain;

import java.time.*;
import java.util.List;
import java.util.Map;

/**
 * Tier set (RF-105..RF-107): fino a 8 condizioni applicate a tutti i tier (unità attive di un wallet, unità maturate
 * totali, spesa totale, mesi dall'adesione, unità maturate nel periodo, campo custom), combinate in AND o OR; ogni tier
 * ha le proprie soglie per condizione; upgrade immediato; modalità di discesa NONE, AUTOMATIC, ANNIVERSARY,
 * CUSTOM_DATES, INTERVAL_MONTHS e ANNUAL_ONE_LEVEL (la discesa morbida di ADR-006, default del programma). Benefici per tier: sconto
 * percentuale, moltiplicatore, premi d'ingresso (RF-76). {@link TierPolicy} resta la vista semplice a soglia STATUS.
 */
public record TierSet(String id, String name, boolean active, List<Condition> conditions, Match match, List<Tier> tiers, Downgrade downgrade) {
    public static final int MAX_CONDITIONS = 8;
    public enum Match { ALL, ANY }
    public enum Metric { ACTIVE_UNITS, TOTAL_EARNED_UNITS, TOTAL_SPENDING, MONTHS_SINCE_JOINING, EARNED_UNITS_IN_PERIOD, CUSTOM_FIELD }
    /** Condizione: metrica + riferimento (wallet, campo) + periodo in mesi per EARNED_UNITS_IN_PERIOD. */
    public record Condition(String id, Metric metric, String reference, int periodMonths) {}
    /** Tier con soglia minima per ogni condizione (id condizione → valore) e benefici. */
    public record Tier(String code, int order, Map<String, Double> thresholds, Benefits benefits) {}
    public record Benefits(Double discountPercent, Double multiplier, List<String> welcomeRewardIds, String description) {}
    public record Downgrade(Mode mode, List<MonthDay> customDates, int intervalMonths) {
        public enum Mode { NONE, AUTOMATIC, ANNIVERSARY, CUSTOM_DATES, INTERVAL_MONTHS, ANNUAL_ONE_LEVEL }
    }

    /** Metriche correnti del membro: id condizione → valore. */
    public record MemberMetrics(Map<String, Double> values, Instant enrolledAt, Instant lastPromotionAt) {}

    public TierSet {
        if (conditions == null || conditions.isEmpty() || conditions.size() > MAX_CONDITIONS) throw new IllegalArgumentException("1.." + MAX_CONDITIONS + " conditions");
        tiers = tiers.stream().sorted((a, b) -> Integer.compare(a.order(), b.order())).toList();
        if (match == null) match = Match.ALL;
    }

    /** Tier qualificato: il più alto le cui soglie sono soddisfatte (tutte o almeno una, secondo {@code match}). */
    public Tier qualified(MemberMetrics m) {
        Tier best = tiers.get(0);
        for (Tier t : tiers) if (satisfies(t, m)) best = t;
        return best;
    }

    boolean satisfies(Tier t, MemberMetrics m) {
        if (t.thresholds() == null || t.thresholds().isEmpty()) return true;
        java.util.stream.Stream<Map.Entry<String, Double>> s = t.thresholds().entrySet().stream();
        java.util.function.Predicate<Map.Entry<String, Double>> ok = e -> m.values() != null && m.values().getOrDefault(e.getKey(), 0d) >= e.getValue();
        return match == Match.ANY ? s.anyMatch(ok) : s.allMatch(ok);
    }

    /** Upgrade immediato: mai discesa in corso d'anno (salvo AUTOMATIC). */
    public Tier duringPeriod(Tier current, MemberMetrics m) {
        Tier q = qualified(m);
        if (downgrade.mode() == Downgrade.Mode.AUTOMATIC) return q;
        return q.order() > current.order() ? q : current;
    }

    /** Alla data di verifica: ricalcolo secondo la modalità di discesa. */
    public Tier atReview(Tier current, MemberMetrics m) {
        Tier q = qualified(m);
        return switch (downgrade.mode()) {
            case NONE -> q.order() > current.order() ? q : current;
            case ANNUAL_ONE_LEVEL -> q.order() >= current.order() ? q : byOrder(Math.max(0, current.order() - 1));
            default -> q;
        };
    }

    /** Prossima data di verifica per il membro secondo la modalità (null = mai). */
    public LocalDate nextReview(MemberMetrics m, LocalDate today) {
        ZoneId rome = ZoneId.of("Europe/Rome");
        return switch (downgrade.mode()) {
            case NONE, AUTOMATIC -> null;
            case ANNIVERSARY -> { LocalDate a = LocalDate.ofInstant(m.enrolledAt(), rome).withYear(today.getYear()); yield a.isAfter(today) ? a : a.plusYears(1); }
            case CUSTOM_DATES -> downgrade.customDates().stream().map(md -> { LocalDate d = md.atYear(today.getYear()); return d.isAfter(today) ? d : d.plusYears(1); }).min(LocalDate::compareTo).orElse(null);
            case INTERVAL_MONTHS -> LocalDate.ofInstant(m.lastPromotionAt() == null ? m.enrolledAt() : m.lastPromotionAt(), rome).plusMonths(downgrade.intervalMonths());
            case ANNUAL_ONE_LEVEL -> LocalDate.of(today.getYear() + 1, 1, 1);
        };
    }

    public Tier byCode(String code) { return tiers.stream().filter(t -> t.code().equals(code)).findFirst().orElse(tiers.get(0)); }
    public Tier byOrder(int order) { return tiers.stream().filter(t -> t.order() == order).findFirst().orElse(tiers.get(0)); }

    /** Progresso verso il tier successivo: per ogni condizione, valore e soglia mancante. */
    public Map<String, Double> missingToNext(Tier current, MemberMetrics m) {
        Tier next = tiers.stream().filter(t -> t.order() == current.order() + 1).findFirst().orElse(null);
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        if (next == null) return out;
        next.thresholds().forEach((k, v) -> out.put(k, Math.max(0, v - (m.values() == null ? 0 : m.values().getOrDefault(k, 0d)))));
        return out;
    }

    /** Il tier set di default del programma (ADR-005/ADR-006): una condizione sui punti STATUS dell'anno, discesa morbida annuale. */
    public static TierSet example() {
        return new TierSet("default", "Programma annuale", true, List.of(new Condition("status", Metric.EARNED_UNITS_IN_PERIOD, "STATUS", 12)), Match.ALL, List.of(
                new Tier("BASE", 0, Map.of("status", 0d), new Benefits(null, 1.0, List.of(), "Accesso al catalogo base")),
                new Tier("PLUS", 1, Map.of("status", 1500d), new Benefits(5.0, 1.25, List.of(), "Sconto 5% sul negozio, punti ×1,25")),
                new Tier("TOP", 2, Map.of("status", 4000d), new Benefits(10.0, 1.5, List.of("welcome-top"), "Sconto 10%, punti ×1,5, premio di benvenuto"))),
                new Downgrade(Downgrade.Mode.ANNUAL_ONE_LEVEL, List.of(), 0));
    }
}
