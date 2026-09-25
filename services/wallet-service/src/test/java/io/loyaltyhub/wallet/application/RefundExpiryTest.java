package io.loyaltyhub.wallet.application;

import io.loyaltyhub.wallet.infra.PointsLotRepository.Consumption;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Scadenza del lotto di rimborso (docs/03 §4.2): max(scadenza più lontana dei lotti consumati, oggi + 30 giorni). */
class RefundExpiryTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant NOW_PLUS_30 = Instant.parse("2026-10-24T10:00:00Z");

    @Test
    void farthestOriginalExpiryWinsWhenLaterThanThirtyDays() {
        Instant oct = Instant.parse("2026-10-31T22:59:59Z");
        Instant mar = Instant.parse("2027-03-31T21:59:59Z");
        List<Consumption> consumed = List.of(new Consumption("L1", 500, "EXHAUSTED", oct),
                new Consumption("L3", 200, "ACTIVE", mar), new Consumption("L2", 800, "EXHAUSTED", Instant.parse("2026-12-31T22:59:59Z")));
        assertThat(RedemptionPayments.refundExpiry(consumed, NOW)).isEqualTo(mar);
    }

    @Test
    void expiredOrSoonExpiringLotsGetThirtyDaysFromToday() {
        List<Consumption> consumed = List.of(new Consumption("L1", 500, "EXPIRED", Instant.parse("2026-08-31T21:59:59Z")),
                new Consumption("L2", 100, "EXHAUSTED", Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(RedemptionPayments.refundExpiry(consumed, NOW)).isEqualTo(NOW_PLUS_30);
    }

    @Test
    void noRecordedConsumptionGetsThirtyDaysAndNonExpiringLotKeepsNoExpiry() {
        assertThat(RedemptionPayments.refundExpiry(List.of(), NOW)).isEqualTo(NOW_PLUS_30);
        assertThat(RedemptionPayments.refundExpiry(List.of(new Consumption("L1", 10, "EXHAUSTED", NOW.minusSeconds(1)),
                new Consumption("L2", 10, "EXHAUSTED", null)), NOW)).isNull();
    }
}
