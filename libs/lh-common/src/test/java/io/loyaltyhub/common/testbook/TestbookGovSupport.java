package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.web.LhException;

import java.util.function.Supplier;

/**
 * Supporto dei test TB-GOV di lh-common (docs/testbook/TB-GOV-governance.md): decodifica dei valori speciali dei CSV e
 * lettura dell'esito ({@code STATO} oppure {@code <http>:<code>}).
 */
final class TestbookGovSupport {

    private TestbookGovSupport() {
    }

    /**
     * Valori speciali delle colonne CSV: {@code NULL} → {@code null}, {@code EMPTY} → "", {@code SPACE} → " ",
     * {@code SPACES} → "   ", {@code TABNL} → "\t\n", {@code NBSP} → U+00A0, {@code LONG} → 2000 caratteri;
     * {@code «…»} conserva gli spazi interni (i CSV tolgono quelli ai bordi dei valori non quotati).
     */
    static String decode(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "NULL" -> null;
            case "EMPTY" -> "";
            case "SPACE" -> " ";
            case "SPACES" -> "   ";
            case "TABNL" -> "\t\n";
            case "NBSP" -> " ";
            case "LONG" -> "x".repeat(2000);
            default -> raw.startsWith("«") && raw.endsWith("»") ? raw.substring(1, raw.length() - 1) : raw;
        };
    }

    /** Esito osservato nello stesso formato dell'atteso: valore restituito oppure {@code <http>:<code>}. */
    static String outcome(Supplier<?> call) {
        try {
            return String.valueOf(call.get());
        } catch (LhException e) {
            return e.status().value() + ":" + e.code();
        }
    }
}
