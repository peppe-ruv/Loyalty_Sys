package io.loyaltyhub.common.web;

/**
 * Il carattere NUL ({@code U+0000}) non è ammesso in nessun testo in ingresso (Q-532 causa (3), F2-SEC-12, ADR-042):
 * Postgres lo rifiuta ({@code invalid byte sequence for encoding "UTF8": 0x00}, per le colonne jsonb
 * {@code unsupported Unicode escape sequence}) e un 500 al posto di un 400 è un difetto di forma del client, non del
 * server. Il rifiuto sta davanti al database, in due punti: {@link NulRejectingFilter} (percorso, nomi e valori dei
 * parametri), {@link NulRejectingModule} (valori e chiavi del corpo JSON).
 */
public final class NulCharacters {

    /** Dettaglio del 400 per un corpo JSON con un NUL; non riporta mai il valore ricevuto. */
    public static final String BODY_MESSAGE = "Il corpo della richiesta contiene il carattere NUL (U+0000), non ammesso";

    /** Dettaglio del 400 per un percorso o un parametro con un NUL; non riporta mai il valore ricevuto. */
    public static final String REQUEST_MESSAGE = "La richiesta contiene il carattere NUL (U+0000), non ammesso";

    private NulCharacters() {
    }

    /** {@code true} se il testo contiene almeno un NUL; {@code null} non lo contiene. */
    public static boolean in(CharSequence text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\0') {
                return true;
            }
        }
        return false;
    }
}
