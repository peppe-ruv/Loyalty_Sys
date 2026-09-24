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
}
