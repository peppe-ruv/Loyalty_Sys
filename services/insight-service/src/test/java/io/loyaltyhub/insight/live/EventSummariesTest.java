package io.loyaltyhub.insight.live;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Sintesi per il rail (docs/servizi/insight-service.md §5): frase leggibile per tipo, tollerante ai campi. */
class EventSummariesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void earnedPointsShowAmountAndCurrency() {
        var data = mapper.readTree("{\"amount\":162,\"currency\":\"PTS\"}");
        assertThat(EventSummaries.of("wallet.points.earned", data)).isEqualTo("Punti accreditati · +162 PTS");
    }

    @Test
    void purchaseShowsAmountInEuro() {
        var data = mapper.readTree("{\"amount\":130,\"currency\":\"EUR\"}");
        assertThat(EventSummaries.of("purchase.completed", data)).isEqualTo("Acquisto · 130 €");
    }

    /** Forma del contratto fact.member.status.changed (EVT-FACT-03): previousStatus / newStatus, non "status". */
    @Test
    void memberStatusChangedShowsPreviousAndNewStatusFromTheContract() {
        var data = mapper.readTree("{\"memberId\":\"MBR-000010\",\"previousStatus\":\"ACTIVE\",\"newStatus\":\"BLOCKED\",\"reason\":\"test\"}");
        assertThat(EventSummaries.of("member.status.changed", data)).isEqualTo("Stato membro: ACTIVE → BLOCKED");
        assertThat(EventSummaries.of("member.status.changed", mapper.readTree("{\"newStatus\":\"ANONYMIZED\"}")))
                .isEqualTo("Stato membro: ANONYMIZED");
        assertThat(EventSummaries.of("member.status.changed", mapper.createObjectNode())).isEqualTo("Stato membro: ?");
    }

    /** fact.tier.upgraded porta newTier (contracts/events/fact/tier.upgraded.schema.json). */
    @Test
    void tierUpgradedShowsTheNewTier() {
        var data = mapper.readTree("{\"previousTier\":\"SILVER\",\"newTier\":\"GOLD\",\"periodSts\":3010}");
        assertThat(EventSummaries.of("tier.upgraded", data)).isEqualTo("Livello → GOLD");
    }

    @Test
    void unknownTypeFallsBackToShortType() {
        assertThat(EventSummaries.of("something.new", mapper.createObjectNode())).isEqualTo("something.new");
    }

    @Test
    void missingFieldsDoNotBreak() {
        assertThat(EventSummaries.of("wallet.points.earned", mapper.createObjectNode()))
                .isEqualTo("Punti accreditati");
        assertThat(EventSummaries.of(null, null)).isEqualTo("evento");
    }

    /**
     * Doppia lettura member.*:1/:2 (ADR-032, Q-346): la versione 2 non ha nome, cognome, soprannome, e-mail, data di
     * nascita e città; la sintesi non li usa in nessuna delle due versioni, quindi è la stessa e non stampa "null".
     */
    @Test
    void memberFactsV2WithoutPersonalFieldsHaveTheSameSummaryAsV1() {
        var v1 = mapper.readTree("""
                {"memberId":"MBR-000003","firstName":"Marco","lastName":"Rossi","nickname":"marco.r",
                 "email":"marco@example.test","birthDate":"1988-04-21","city":"Torino","status":"ACTIVE"}""");
        var v2 = mapper.readTree("""
                {"memberId":"MBR-000003","externalId":"CRM-3003","emailHash":"%s","status":"ACTIVE","channel":"APP",
                 "locale":"it","birthYear":1988,"province":"MI","labels":["early-adopter"]}""".formatted("a".repeat(64)));
        var v2Minimal = mapper.readTree("""
                {"memberId":"MBR-000003","status":"ANONYMIZED","birthYear":null,"province":null,"referredBy":null}""");

        for (String type : new String[]{"member.registered", "member.updated"}) {
            String expected = EventSummaries.of(type, v1);
            assertThat(EventSummaries.of(type, v2)).isEqualTo(expected).doesNotContain("null");
            assertThat(EventSummaries.of(type, v2Minimal)).isEqualTo(expected).doesNotContain("null");
            assertThat(EventSummaries.of(type, mapper.createObjectNode())).isEqualTo(expected);
            assertThat(EventSummaries.of(type, null)).isEqualTo(expected);
        }
        assertThat(EventSummaries.of("member.registered", v2)).isEqualTo("Nuovo membro");
        assertThat(EventSummaries.of("member.updated", v2)).isEqualTo("Membro aggiornato");
        // Nessun dato personale nella sintesi della versione 1 (il rail è visibile a tutto il backoffice).
        assertThat(EventSummaries.of("member.registered", v1)).doesNotContain("Marco", "Rossi", "marco");
    }
}
