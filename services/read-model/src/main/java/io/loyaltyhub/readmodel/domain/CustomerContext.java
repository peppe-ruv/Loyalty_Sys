package io.loyaltyhub.readmodel.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Customer 360 (RF-125): profilo aggregato del membro, aggiornato dagli eventi, letto in una sola chiamata dal motore
 * decisionale (RF-126) e dal sito. Sezioni: identità loyalty (nessuna anagrafica, ADR-012), loyalty (wallet, tier),
 * comportamento (azioni recenti, RFM), engagement (badge, achievement, campagne, offerte recenti), rischio,
 * consensi, previsioni (calcolate a lettura dal decision-service, qui solo l'ultimo valore noto).
 */
public record CustomerContext(
        String memberId,
        Identity identity,
        Loyalty loyalty,
        Behaviour behaviour,
        Engagement engagement,
        Risk risk,
        Map<String, Boolean> consents,
        Map<String, Double> predictions,
        Instant updatedAt
) {
    public record Identity(String status, Instant enrolledAt, String channel, String referredBy, Map<String, String> labels, Map<String, Object> customFields) {}
    public record Wallet(long active, long earned, long spent, long pending, long blocked, long expired) {}
    public record Loyalty(Map<String, Wallet> wallets, String tier, long statusPointsYear, Instant tierSince, List<String> segments) {}
    public record ActionSummary(String actionType, Instant occurredAt, String channel, Double amountEur, String ref) {}
    /** RFM (RF-125): recency in giorni dall'ultima transazione, frequenza a 90/365 giorni, monetary a 365 giorni. */
    public record Rfm(Integer recencyDays, int frequency90d, int frequency365d, double monetary365d, Instant firstTransactionAt, Instant lastTransactionAt) {}
    public record Behaviour(List<ActionSummary> recentActions, Rfm rfm, Map<String, Integer> actionCounts30d, String preferredChannel) {}
    public record Offer(String decisionId, String action, String reference, String channel, Instant at, String outcome) {}
    public record Engagement(List<String> badges, Map<String, Integer> achievementsCompleted, Map<String, Integer> challengesCompleted, List<String> campaignsCompleted30d, List<Offer> recentOffers, int redemptions90d, int contestPlays30d, Instant lastContactAt, Map<String, Integer> contacts7dByChannel) {}
    public record Risk(int score, String level, List<String> reasonCodes, Instant assessedAt) {
        public static final Risk NONE = new Risk(0, "LOW", List.of(), null);
    }
}
