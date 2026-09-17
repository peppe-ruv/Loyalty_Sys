package it.iren.loyalty.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Wallet del programma (D05, RF-87): PREMIO si spende, STATUS qualifica il tier e non si spende. Da RF-87 i wallet sono
 * configurabili dal backoffice (bollini, unità di campagna, ecc.): questo tipo identifica un wallet per codice; i due
 * codici storici restano costanti e comportamento di default.
 */
public record Currency(@JsonValue String code) {
    public static final Currency PREMIO = new Currency("PREMIO");
    public static final Currency STATUS = new Currency("STATUS");

    public Currency {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,31}")) throw new IllegalArgumentException("wallet code must be UPPER_SNAKE, 2-32 chars: " + code);
    }

    @JsonCreator public static Currency of(String code) { return new Currency(code == null ? null : code.trim().toUpperCase()); }
    public static Currency valueOf(String code) { return of(code); }
    public String name() { return code; }
    @Override public String toString() { return code; }
}
