package io.loyaltyhub.ledger.domain;

import java.time.*;

/**
 * Tipo di wallet configurabile dal backoffice (RF-87): codice, nomi
 * dell'unità (singolare/plurale), scadenza, sospensione, limiti, saldo negativo, stato. PREMIO e STATUS sono i wallet
 * di default (ADR-005) e non si disattivano; un wallet in più (es. BOLLINI di una raccolta) si aggiunge senza codice.
 */
public record WalletType(String code, String name, String unitSingular, String unitPlural, Expiration expiration, int expirationDays, MonthDay expirationAnnualDate,
                         int pendingDays, boolean allowNegative, long globalLimit, long perMemberLimit, boolean active, boolean isDefault, boolean spendable) {
    public enum Expiration { NONE, AFTER_DAYS, END_OF_MONTH, END_OF_YEAR, ANNUAL_DATE, END_OF_NEXT_PROGRAM_YEAR }

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    /** Scadenza di un accredito maturato all'istante dato (RF-09, RF-87); null = non scade. */
    public Instant expiresAtFor(Instant earnedAt) {
        ZonedDateTime z = earnedAt.atZone(ROME);
        return switch (expiration) {
            case NONE -> null;
            case AFTER_DAYS -> earnedAt.plus(Duration.ofDays(expirationDays));
            case END_OF_MONTH -> z.toLocalDate().withDayOfMonth(z.toLocalDate().lengthOfMonth()).plusDays(1).atStartOfDay(ROME).toInstant();
            case END_OF_YEAR -> LocalDate.of(z.getYear() + 1, 1, 1).atStartOfDay(ROME).toInstant();
            case END_OF_NEXT_PROGRAM_YEAR -> LocalDate.of(z.getYear() + 2, 1, 1).atStartOfDay(ROME).toInstant();
            case ANNUAL_DATE -> {
                LocalDate d = expirationAnnualDate.atYear(z.getYear());
                if (!d.isAfter(z.toLocalDate())) d = d.plusYears(1);
                yield d.atStartOfDay(ROME).toInstant();
            }
        };
    }

    /** Sospensione di default (RF-66): null = subito disponibile. */
    public Instant pendingUntilFor(Instant earnedAt) { return pendingDays > 0 ? earnedAt.plus(Duration.ofDays(pendingDays)) : null; }

    public static WalletType premio() { return new WalletType("PREMIO", "Punti premio", "punto", "punti", Expiration.END_OF_NEXT_PROGRAM_YEAR, 0, null, 0, false, 0, 0, true, true, true); }
    public static WalletType status() { return new WalletType("STATUS", "Punti status", "punto status", "punti status", Expiration.END_OF_YEAR, 0, null, 0, false, 0, 0, true, true, false); }
}
