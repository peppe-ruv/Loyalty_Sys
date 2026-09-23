package io.loyaltyhub.wallet.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.Function;

/**
 * Politica di scadenza di una valuta (docs/03 §4.2, docs/servizi/wallet-service.md §5), in {@code Europe/Rome}:
 * <ul>
 *   <li>{@code ROLLING_MONTHS(n)}: ultimo istante del mese di {@code earnedAt + n mesi};</li>
 *   <li>{@code END_OF_EDITION_PLUS_GRACE}: ultimo istante di {@code redemptionGraceUntil} dell'edizione che contiene
 *       {@code earnedAt} (docs/03 §4.2); se l'edizione non ha data di tolleranza, {@code endDate + graceDays};</li>
 *   <li>{@code NEVER} ed {@code EDITION} (gli STS: si azzerano con la chiusura, non scadono a lotto): nessuna scadenza.</li>
 * </ul>
 */
public final class ExpiryPolicy {

    /** Le date di business della loyalty sono in ora italiana (docs §5: "fine mese in Europe/Rome"). */
    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    public static final String ROLLING_MONTHS = "ROLLING_MONTHS";
    public static final String END_OF_EDITION_PLUS_GRACE = "END_OF_EDITION_PLUS_GRACE";

    private ExpiryPolicy() {
    }

    /** {@code expires_at} per un lotto guadagnato in {@code earnedAt}, senza edizioni note; {@code null} = non scade. */
    public static Instant expiresAt(JsonNode policy, Instant earnedAt) {
        return expiresAt(policy, earnedAt, day -> Optional.empty());
    }

    /**
     * {@code expires_at} per un lotto guadagnato in {@code earnedAt}; {@code editionAt} risolve l'edizione che
     * contiene un giorno. {@code null} = non scade.
     */
    public static Instant expiresAt(JsonNode policy, Instant earnedAt, Function<LocalDate, Optional<Edition>> editionAt) {
        if (policy == null || earnedAt == null) {
            return null;
        }
        String type = policy.path("type").asString("NEVER");
        if (ROLLING_MONTHS.equals(type)) {
            int months = policy.path("months").asInt(12);
            ZonedDateTime shifted = earnedAt.atZone(ZONE).plusMonths(months);
            return YearMonth.from(shifted).atEndOfMonth().atTime(LocalTime.MAX).atZone(ZONE).toInstant();
        }
        if (END_OF_EDITION_PLUS_GRACE.equals(type)) {
            LocalDate earnedDay = earnedAt.atZone(ZONE).toLocalDate();
            Optional<Edition> edition = editionAt.apply(earnedDay);
            if (edition.isEmpty()) {
                // SPEC-GAP: Q-47 — nessuna edizione copre la data: scelta conservativa, il lotto non scade.
                return null;
            }
            Edition e = edition.get();
            LocalDate lastDay = e.redemptionGraceUntil() != null
                    ? e.redemptionGraceUntil()
                    : (e.endDate() == null ? null : e.endDate().plusDays(Math.max(0, policy.path("graceDays").asInt(0))));
            return lastDay == null ? null : lastDay.atTime(LocalTime.MAX).atZone(ZONE).toInstant();
        }
        return null; // EDITION / NEVER
    }
}
