package io.loyaltyhub.wallet.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Politiche di scadenza dei lotti (docs/03 §4.2). */
class ExpiryPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Edition ED_2026 = new Edition("ED-2026", "Edizione 2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31), Edition.ACTIVE);

    private static JsonNode policy(String json) {
        return MAPPER.readTree(json);
    }

    private static Instant endOfDayRome(LocalDate day) {
        return day.atTime(LocalTime.MAX).atZone(ExpiryPolicy.ZONE).toInstant();
    }

    @Test
    void rollingMonthsExpiresAtEndOfShiftedMonth() {
        Instant earned = LocalDate.of(2026, 9, 23).atStartOfDay(ExpiryPolicy.ZONE).toInstant();
        assertThat(ExpiryPolicy.expiresAt(policy("{\"type\":\"ROLLING_MONTHS\",\"months\":12}"), earned))
                .isEqualTo(endOfDayRome(LocalDate.of(2027, 9, 30)));
    }

    @Test
    void endOfEditionPlusGraceExpiresAtEditionGraceDate() {
        Instant earned = LocalDate.of(2026, 9, 23).atStartOfDay(ExpiryPolicy.ZONE).toInstant();
        Instant expires = ExpiryPolicy.expiresAt(policy("{\"type\":\"END_OF_EDITION_PLUS_GRACE\",\"graceDays\":10}"),
                earned, day -> day.getYear() == 2026 ? Optional.of(ED_2026) : Optional.empty());
        assertThat(expires).isEqualTo(endOfDayRome(LocalDate.of(2027, 1, 31)));
    }

    @Test
    void endOfEditionPlusGraceFallsBackToEndDatePlusGraceDays() {
        Edition noGrace = new Edition("ED-X", "X", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, Edition.ACTIVE);
        Instant earned = LocalDate.of(2026, 3, 1).atStartOfDay(ExpiryPolicy.ZONE).toInstant();
        Instant expires = ExpiryPolicy.expiresAt(policy("{\"type\":\"END_OF_EDITION_PLUS_GRACE\",\"graceDays\":10}"),
                earned, day -> Optional.of(noGrace));
        assertThat(expires).isEqualTo(endOfDayRome(LocalDate.of(2027, 1, 10)));
    }

    @Test
    void endOfEditionPlusGraceWithoutCoveringEditionDoesNotExpire() {
        Instant earned = LocalDate.of(2030, 3, 1).atStartOfDay(ExpiryPolicy.ZONE).toInstant();
        assertThat(ExpiryPolicy.expiresAt(policy("{\"type\":\"END_OF_EDITION_PLUS_GRACE\"}"),
                earned, day -> Optional.empty())).isNull();
    }

    @Test
    void neverAndEditionDoNotExpire() {
        Instant earned = Instant.parse("2026-09-23T10:00:00Z");
        assertThat(ExpiryPolicy.expiresAt(policy("{\"type\":\"NEVER\"}"), earned, day -> Optional.of(ED_2026))).isNull();
        assertThat(ExpiryPolicy.expiresAt(policy("{\"type\":\"EDITION\"}"), earned, day -> Optional.of(ED_2026))).isNull();
    }
}
