package io.loyaltyhub.ingestion.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Lettore CSV secondo RFC 4180, un record alla volta (niente elenco di tutte le righe in memoria): campi tra virgolette
 * con {@code ""} per le virgolette e a capo ammessi, terminatori CRLF o LF, separatore scelto dal chiamante. Tiene la
 * linea fisica dove comincia ogni record ({@link #recordLine()}). Virgolette aperte e mai chiuse a fine testo ⇒
 * {@link ImportFileException} {@code IMPORT_INVALID}.
 */
final class CsvReader {

    private final String text;
    private final char delimiter;
    private int pos;
    private int line = 1;
    private int recordLine;

    CsvReader(String text, char delimiter) {
        this.text = text;
        this.delimiter = delimiter;
    }

    /**
     * Separatore dalla prima riga: {@code ;} se ce ne sono più delle virgole (esportazioni di fogli di calcolo italiani),
     * altrimenti {@code ,}.
     */
    static char delimiterOf(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        long commas = first.chars().filter(c -> c == ',').count();
        long semicolons = first.chars().filter(c -> c == ';').count();
        return semicolons > commas ? ';' : ',';
    }

    /** Linea fisica (da 1) dove comincia l'ultimo record restituito da {@link #next()}. */
    int recordLine() {
        return recordLine;
    }

    /** Prossimo record (celle), {@code null} a fine testo. */
    List<String> next() {
        int n = text.length();
        if (pos >= n) {
            return null;
        }
        recordLine = line;
        List<String> cells = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        while (pos < n) {
            char c = text.charAt(pos);
            if (quoted) {
                if (c == '"') {
                    if (pos + 1 < n && text.charAt(pos + 1) == '"') {
                        field.append('"');
                        pos += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                pos++;
                continue;
            }
            if (c == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == delimiter) {
                cells.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r' || c == '\n') {
                pos += c == '\r' && pos + 1 < n && text.charAt(pos + 1) == '\n' ? 2 : 1;
                line++;
                cells.add(field.toString());
                return cells;
            } else {
                field.append(c);
                fieldStarted = true;
            }
            pos++;
        }
        if (quoted) {
            throw new ImportFileException("IMPORT_INVALID", "CSV non valido: virgolette aperte e mai chiuse.");
        }
        cells.add(field.toString());
        return cells;
    }

    /** Record vuoto (riga bianca): un solo campo senza contenuto. */
    static boolean blank(List<String> cells) {
        return cells.size() == 1 && cells.getFirst().isBlank();
    }
}
