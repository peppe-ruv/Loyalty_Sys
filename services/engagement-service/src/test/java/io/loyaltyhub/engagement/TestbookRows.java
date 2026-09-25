package io.loyaltyhub.engagement;

import org.junit.jupiter.params.aggregator.ArgumentsAccessor;

/**
 * Righe del testbook TB-ENG (docs/16 §1bis) nei CSV di {@code src/test/resources/testbook/engagement/}: separatore TAB,
 * virgolette {@code ~} (i valori contengono JSON, virgole e barre verticali), prima riga di intestazione, prima colonna
 * l'ID della riga e seconda la descrizione; ogni riga è un caso {@code @ParameterizedTest(name = "[{0}] {1}")}.
 */
public final class TestbookRows {

    private TestbookRows() {
    }

    /** Le colonne della riga come testo (indice 0 = ID). */
    public static String[] columns(ArgumentsAccessor row) {
        String[] c = new String[row.size()];
        for (int i = 0; i < c.length; i++) {
            c[i] = row.getString(i);
        }
        return c;
    }
}
