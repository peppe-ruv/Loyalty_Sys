package io.loyaltyhub.common.testbook;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;

/**
 * Supporto dei test TB-PLT di lh-common (docs/testbook/TB-PLT-piattaforma.md): orologi fissi e lettura uniforme
 * dell'esito di un caso (valore restituito oppure {@code ERR:<classe semplice dell'eccezione>}).
 */
final class TestbookPltSupport {

    private TestbookPltSupport() {
    }

    /** Orologio fermo sull'istante UTC indicato. */
    static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    /** Esito osservato: {@code String.valueOf(risultato)} oppure {@code ERR:<SimpleName>} se il caso solleva. */
    static String outcome(Callable<?> call) {
        try {
            return String.valueOf(call.call());
        } catch (Exception e) {
            return "ERR:" + e.getClass().getSimpleName();
        }
    }

    /** Valori speciali delle colonne CSV: {@code NULL} → {@code null}, {@code EMPTY} → "", {@code SPACES} → "   ". */
    static String decode(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "NULL" -> null;
            case "EMPTY" -> "";
            case "SPACES" -> "   ";
            default -> raw.startsWith("«") && raw.endsWith("»") ? raw.substring(1, raw.length() - 1) : raw;
        };
    }
}
