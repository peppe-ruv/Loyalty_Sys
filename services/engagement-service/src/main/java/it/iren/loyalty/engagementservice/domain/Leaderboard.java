package it.iren.loyalty.engagementservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Classifica (RF-93): metrica tracciata (unità maturate in un wallet, transazioni, valore transazioni, eventi custom,
 * progresso achievement) in un periodo, senza ricalcolo retroattivo, raggruppamento opzionale per etichetta/campo del
 * membro (es. provincia), top 1000 per gruppo, ricalcolo ogni 4 ore, ciclo premiante opzionale (premi per posizione a
 * fine ciclo, per gruppo). Massimo 3 classifiche attive; visibilità come le campagne.
 */
public record Leaderboard(String id, String name, boolean active, Metric metric, String reference, Instant startsAt, Instant endsAt,
                          String groupBy, int topN, RewardingCycle rewardingCycle, String visibility) {
    public enum Metric { UNITS_EARNED, TRANSACTIONS_COUNT, TRANSACTIONS_VALUE, CUSTOM_EVENTS_COUNT, ACHIEVEMENT_PROGRESS }
    /** Premi per fascia di posizione (1..N) a ogni chiusura di ciclo. */
    public record RewardingCycle(Achievement.Period period, List<RankReward> rewards) {}
    public record RankReward(int fromRank, int toRank, String rewardId, String wallet, long units, String badgeCode) {}

    public Leaderboard {
        if (topN <= 0 || topN > 1000) topN = 1000;
    }

    /** Punteggio di un membro nel periodo (gruppo opzionale). */
    public record Score(String memberId, String group, double value) {}
    public record Entry(int rank, String memberId, String group, double value) {}

    /**
     * Classifica per gruppo con "competition ranking": chi ha lo stesso valore condivide la posizione (1,1,3).
     */
    public static Map<String, List<Entry>> rank(List<Score> scores, int topN) {
        Map<String, List<Score>> byGroup = new java.util.TreeMap<>();
        for (Score s : scores) {
            if (s.group() == null || s.group().isBlank()) { if (scores.stream().anyMatch(x -> x.group() != null && !x.group().isBlank())) continue; }
            byGroup.computeIfAbsent(s.group() == null ? "" : s.group(), k -> new java.util.ArrayList<>()).add(s);
        }
        Map<String, List<Entry>> out = new java.util.LinkedHashMap<>();
        byGroup.forEach((g, list) -> {
            list.sort((a, b) -> Double.compare(b.value(), a.value()));
            List<Entry> entries = new java.util.ArrayList<>();
            int rank = 0; double prev = Double.NaN;
            for (int i = 0; i < list.size() && entries.size() < topN; i++) {
                Score s = list.get(i);
                if (s.value() != prev) { rank = i + 1; prev = s.value(); }
                entries.add(new Entry(rank, s.memberId(), g, s.value()));
            }
            out.put(g, entries);
        });
        return out;
    }

    /** Premi dovuti a fine ciclo per una classifica di gruppo. */
    public List<Map.Entry<Entry, RankReward>> rewardsFor(List<Entry> entries) {
        List<Map.Entry<Entry, RankReward>> out = new java.util.ArrayList<>();
        if (rewardingCycle == null) return out;
        for (Entry e : entries) for (RankReward r : rewardingCycle.rewards()) if (e.rank() >= r.fromRank() && e.rank() <= r.toRank()) out.add(Map.entry(e, r));
        return out;
    }
}
