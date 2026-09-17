package it.iren.loyalty.decisionservice.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Esperimento (RF-134): confronto tra un gruppo di controllo e una o più varianti di policy decisionale, su un
 * sottoinsieme di eventi/segmenti. L'assegnazione è deterministica (hash di membro+esperimento) e quindi stabile e
 * riproducibile; ogni decisione registra esperimento e variante e viene emesso EXPERIMENT_EXPOSED, così la BI misura
 * le metriche incrementali (RF-121) per variante. Configurato nel backoffice (collezione {@code experiments}).
 *
 * @param trafficPercent quota di membri esposti (il resto non partecipa)
 * @param eventTypes tipi di evento coinvolti (vuoto = tutti); segments: solo membri in questi segmenti (vuoto = tutti)
 */
public record Experiment(String id, String name, boolean active, Instant startsAt, Instant endsAt, int trafficPercent,
                         Set<String> eventTypes, Set<String> segments, List<Variant> variants, String primaryMetric) {

    /**
     * @param control variante di controllo (nessuna modifica alla policy)
     * @param policyId policy da usare al posto di quella di default (null = default)
     * @param overrides sovrascritture puntuali della policy (es. {@code scoring.propensityWeight=3}, {@code maxArbitratedPerEvent=2})
     */
    public record Variant(String name, int weight, boolean control, String policyId, Map<String, Object> overrides) {}

    public record Assignment(String experimentId, Variant variant) {}

    public boolean inWindow(Instant now) {
        if (!active) return false;
        if (startsAt != null && now.isBefore(startsAt)) return false;
        return endsAt == null || !now.isAfter(endsAt);
    }

    public boolean appliesTo(String eventType, List<String> memberSegments, Instant now) {
        if (!inWindow(now)) return false;
        if (eventTypes != null && !eventTypes.isEmpty() && (eventType == null || !eventTypes.contains(eventType))) return false;
        if (segments != null && !segments.isEmpty()) {
            if (memberSegments == null) return false;
            return memberSegments.stream().anyMatch(segments::contains);
        }
        return true;
    }

    /** Assegnazione deterministica: bucket 0..9999 da SHA-256(memberId:experimentId); null se fuori traffico o senza varianti. */
    public Variant assign(String memberId) {
        if (variants == null || variants.isEmpty()) return null;
        int bucket = bucket(memberId + ":" + id);
        if (bucket >= trafficPercent * 100) return null;
        int total = variants.stream().mapToInt(Variant::weight).sum();
        if (total <= 0) return variants.get(0);
        int slot = bucket(id + ":" + memberId) % total, acc = 0;
        for (Variant v : variants) { acc += v.weight(); if (slot < acc) return v; }
        return variants.get(variants.size() - 1);
    }

    static int bucket(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            int v = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
            return Math.floorMod(v, 10_000);
        } catch (Exception e) { return Math.floorMod(s.hashCode(), 10_000); }
    }

    /** Applica le sovrascritture della variante alla policy (solo campi di scoring e limiti: mai le azioni contrattuali). */
    public static DecisionPolicy apply(DecisionPolicy base, Variant v) {
        if (v == null || v.overrides() == null || v.overrides().isEmpty()) return base;
        var o = v.overrides();
        var s = base.scoring();
        DecisionPolicy.Scoring scoring = new DecisionPolicy.Scoring(
                o.containsKey("scoring.strategy") ? DecisionPolicy.Scoring.Strategy.valueOf(String.valueOf(o.get("scoring.strategy"))) : s.strategy(),
                num(o, "scoring.valueWeight", s.valueWeight()), num(o, "scoring.costWeight", s.costWeight()), num(o, "scoring.propensityWeight", s.propensityWeight()),
                num(o, "scoring.churnWeight", s.churnWeight()), num(o, "scoring.recencyWeight", s.recencyWeight()), s.tierBoost(),
                num(o, "scoring.channelPreferenceBonus", s.channelPreferenceBonus()),
                o.containsKey("scoring.expression") ? String.valueOf(o.get("scoring.expression")) : s.expression());
        int max = (int) num(o, "maxArbitratedPerEvent", base.maxArbitratedPerEvent());
        var k = base.constraints();
        DecisionPolicy.Constraints constraints = k == null ? null : new DecisionPolicy.Constraints(k.contactCap7dByChannel(), k.quietHoursFrom(), k.quietHoursTo(), k.dailyUnitsBudget(),
                k.suppressionSegments(), k.blockRiskLevel(), (int) num(o, "constraints.minHoursBetweenOffers", k.minHoursBetweenOffers()));
        return new DecisionPolicy(base.id(), base.version() + "+" + v.name(), base.active(), base.actions(), constraints, scoring, base.alwaysApply(), max, base.channelPreferenceOrder());
    }

    private static double num(Map<String, Object> o, String key, double fallback) {
        Object v = o.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String str) { try { return Double.parseDouble(str); } catch (NumberFormatException e) { return fallback; } }
        return fallback;
    }
}
