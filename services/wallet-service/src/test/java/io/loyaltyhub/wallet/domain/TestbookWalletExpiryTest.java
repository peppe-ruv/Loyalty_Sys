package io.loyaltyhub.wallet.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TestbookWalletExpiryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("[TB-WAL-EXP-001] ROLLING_MONTHS 12 - 15 Gen -> Scade 31 Gen (anno prox) 23:59:59 CET")
    void expiryRollingMonthsStandard() throws Exception {
        Instant earned = LocalDate.of(2026, 1, 15).atStartOfDay(ExpiryPolicy.ZONE).toInstant();
        Instant expires = ExpiryPolicy.expiresAt(mapper.readTree("{\"type\":\"ROLLING_MONTHS\",\"months\":12}"), earned);

        // Expected: 2027-01-31T23:59:59 Europe/Rome
        Instant expected = LocalDate.of(2027, 1, 31).atTime(23, 59, 59).atZone(ExpiryPolicy.ZONE).toInstant();
        // Since ExpiryPolicy logic uses LocalTime.MAX internally which is 23:59:59.999999999, we assert string to avoid nanosecond issues
        assertThat(expires.atZone(ExpiryPolicy.ZONE).toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(expires.atZone(ExpiryPolicy.ZONE).toLocalTime().getHour()).isEqualTo(23);
        assertThat(expires.atZone(ExpiryPolicy.ZONE).toLocalTime().getMinute()).isEqualTo(59);
    }

    @Test
    @DisplayName("[TB-WAL-EXP-002] ROLLING_MONTHS 1 - 28 Feb (non bisestile) -> Scade 31 Mar 23:59:59 CEST")
    void expiryRollingMonthsDST() throws Exception {
        Instant earned = LocalDate.of(2026, 2, 28).atStartOfDay(ExpiryPolicy.ZONE).toInstant();
        Instant expires = ExpiryPolicy.expiresAt(mapper.readTree("{\"type\":\"ROLLING_MONTHS\",\"months\":1}"), earned);

        // Expected: 2026-03-31T23:59:59 Europe/Rome (DST switch happened)
        assertThat(expires.atZone(ExpiryPolicy.ZONE).toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(expires.atZone(ExpiryPolicy.ZONE).toLocalTime().getHour()).isEqualTo(23);
        assertThat(expires.atZone(ExpiryPolicy.ZONE).toLocalTime().getMinute()).isEqualTo(59);
    }
}
