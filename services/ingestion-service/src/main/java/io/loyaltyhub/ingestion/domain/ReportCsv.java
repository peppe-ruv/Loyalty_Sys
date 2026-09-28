package io.loyaltyhub.ingestion.domain;

import java.util.List;

/**
 * Celle del rapporto esiti scaricabile di un import (BO-32, F2-ING-02). Il rapporto si apre in un foglio di calcolo,
 * quindi ogni cella che comincia con {@code = + - @}, tabulazione o ritorno a capo è preceduta da un apostrofo (difesa
 * dalla <em>formula injection</em>, OWASP «CSV Injection»): il testo arriva da file e sistemi esterni. Poi si applica il
 * quoting di RFC 4180.
 */
public final class ReportCsv {

    private static final String FORMULA_START = "=+-@\t\r";

    private ReportCsv() {
    }

    /** Cella sicura: neutralizzata come formula e quotata se contiene separatori, virgolette o a capo. */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = FORMULA_START.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean quote = safe.indexOf(',') >= 0 || safe.indexOf(';') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    /** Una riga CSV (separatore virgola, terminatore {@code \n}). */
    public static String line(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(values.get(i)));
        }
        return sb.append('\n').toString();
    }
}
