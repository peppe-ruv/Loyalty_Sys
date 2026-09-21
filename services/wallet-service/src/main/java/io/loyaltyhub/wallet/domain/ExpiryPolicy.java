package io.loyaltyhub.wallet.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Politica di scadenza di una valuta (docs/03 §4.2, docs/servizi/wallet-service.md §5). In M3.1 è supportata
 * {@code ROLLING_MONTHS(n)}: la scadenza è l'ultimo istante del mese di {@code earnedAt + n mesi}, in
 * {@code Europe/Rome}. {@code NEVER} e le policy legate all'edizione ({@code EDITION},
 * {@code END_OF_EDITION_PLUS_GRACE}) non danno scadenza qui: arrivano con le edizioni (M3.4).
 */
public final class ExpiryPolicy {

    /** Le date di business della loyalty sono in ora italiana (docs §5: "fine mese in Europe/Rome"). */
    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    public static final String ROLLING_MONTHS = "ROLLING_MONTHS";

    private ExpiryPolicy() {
    }

    /** {@code expires_at} per un lotto guadagnato in {@code earnedAt}; {@code null} = non scade. */
    public static Instant expiresAt(JsonNode policy, Instant earnedAt) {
        if (policy == null || earnedAt == null) {
            return null;
        }
        String type = policy.path("type").asString("NEVER");
        if (ROLLING_MONTHS.equals(type)) {
            int months = policy.path("months").asInt(12);
            ZonedDateTime shifted = earnedAt.atZone(ZONE).plusMonths(months);
            return YearMonth.from(shifted).atEndOfMonth().atTime(LocalTime.MAX).atZone(ZONE).toInstant();
        }
        return null; // EDITION / END_OF_EDITION_PLUS_GRACE / NEVER: M3.4 o nessuna scadenza
    }
}
