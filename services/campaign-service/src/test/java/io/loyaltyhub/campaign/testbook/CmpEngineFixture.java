package io.loyaltyhub.campaign.testbook;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import io.loyaltyhub.campaign.engine.CampaignEngine;
import io.loyaltyhub.campaign.engine.Counters;
import io.loyaltyhub.campaign.engine.EvalAction;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supporto dei casi unitari del testbook TB-CMP (docs/testbook/TB-CMP-campagne.md): costruisce campagne, membri,
 * azioni e contatori deterministici per il motore puro {@link CampaignEngine} (docs/03 §3.5), senza Spring e senza
 * orologio di sistema (ogni istante è esplicito).
 */
final class CmpEngineFixture {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Martedì 15 settembre 2026, 11:00 a Roma: istante neutro quando la riga non parla di tempo. */
    static final Instant TUESDAY = Instant.parse("2026-09-15T09:00:00Z");
    static final String TYPE = "purchase.completed";
    static final String SOURCE = "urn:loyaltyhub:source:ecommerce";
    static final String GRANT_100 = "[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":100}]";

    private CmpEngineFixture() {
    }

    static JsonNode json(String text) {
        return text == null || text.isBlank() ? null : JSON.readTree(text);
    }

    static CampaignEngine engine() {
        return new CampaignEngine(5);
    }

    /** Campagna {@code LIVE} con i soli campi che il motore legge; i JSON {@code null} restano assenti. */
    static Campaign campaign(String code, int priority, List<String> triggers, String audience, String conditions,
                             String effects, String limits, String schedule, String exclusiveGroup, List<String> labels) {
        return new Campaign("ID-" + code, code, "Campagna " + code, null, null, null, triggers,
                json(audience), json(conditions), json(effects == null ? GRANT_100 : effects), json(limits),
                json(schedule), priority, exclusiveGroup, false, false, false,
                labels == null ? List.of() : labels, CampaignStatus.LIVE, 0, null, null);
    }

    /** Campagna standard su {@link #TYPE}: tutti, nessuna condizione, +100 PTS, senza limiti né calendario. */
    static Campaign simple(String code) {
        return campaign(code, 100, List.of(TYPE), null, null, null, null, null, null, null);
    }

    static MemberSnapshot member(String status, String tier, List<String> segments) {
        return new MemberSnapshot("MBR-900001", status, tier, segments, List.of(), JSON.createObjectNode(),
                Instant.parse("2025-01-10T10:00:00Z"), LocalDate.parse("1990-05-20"));
    }

    static MemberSnapshot silver() {
        return member("ACTIVE", "SILVER", List.of("SEG-A", "SEG-B"));
    }

    static MemberSnapshot full(List<String> labels, String attributes, Instant registeredAt, LocalDate birthDate) {
        return new MemberSnapshot("MBR-900001", "ACTIVE", "SILVER", List.of("SEG-A", "SEG-B"), labels,
                attributes == null ? JSON.createObjectNode() : json(attributes), registeredAt, birthDate);
    }

    static EvalAction action(String type, Instant time, String data) {
        return new EvalAction("ACT-TB-0001", type, "MBR-900001", SOURCE, time,
                data == null ? JSON.createObjectNode() : json(data));
    }

    static EvalAction action(String actionId, String type, Instant time, String data) {
        return new EvalAction(actionId, type, "MBR-900001", SOURCE, time,
                data == null ? JSON.createObjectNode() : json(data));
    }

    static List<String> list(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).toList();
    }

    static Evaluation.CampaignResult result(Evaluation ev, String code) {
        return ev.results().stream().filter(r -> r.campaignCode().equals(code)).findFirst().orElse(null);
    }

    /** Esito sintetico di una campagna: {@code MATCHED}, il motivo di scarto, oppure {@code ABSENT} se non candidata. */
    static String outcomeOf(Evaluation ev, String code) {
        Evaluation.CampaignResult r = result(ev, code);
        if (r == null) {
            return "ABSENT";
        }
        return r.matched() ? "MATCHED" : r.reason().name();
    }

    /**
     * Contatori deterministici: {@code memberCounts} "PERIODO:chiave=n;…" (es. {@code DAY:2026-03-28=1}), punti e match
     * globali, storico azioni ({@code daysSince} −1 = mai).
     */
    static final class StubCounters implements Counters {
        final Map<String, Integer> memberCounts = new HashMap<>();
        long globalPoints;
        long globalMatches;
        long historyCount;
        long daysSince = -1;

        static StubCounters zero() {
            return new StubCounters();
        }

        static StubCounters of(String memberCounts, Long globalPoints, Long globalMatches) {
            StubCounters c = new StubCounters();
            if (memberCounts != null && !memberCounts.isBlank()) {
                for (String part : memberCounts.split(";")) {
                    String[] kv = part.trim().split("=");
                    c.memberCounts.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                }
            }
            c.globalPoints = globalPoints == null ? 0 : globalPoints;
            c.globalMatches = globalMatches == null ? 0 : globalMatches;
            return c;
        }

        @Override
        public int memberMatches(String campaignId, String memberId, String period, String periodKey) {
            return memberCounts.getOrDefault(period + ":" + periodKey, 0);
        }

        @Override
        public long globalPointsDecided(String campaignId) {
            return globalPoints;
        }

        @Override
        public long globalMatches(String campaignId) {
            return globalMatches;
        }

        @Override
        public long historyActionCount(String memberId, String actionType) {
            return historyCount;
        }

        @Override
        public long historyDaysSinceLastAction(String memberId, String actionType) {
            return daysSince;
        }
    }
}
