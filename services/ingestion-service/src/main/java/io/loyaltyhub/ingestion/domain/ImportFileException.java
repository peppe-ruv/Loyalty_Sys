package io.loyaltyhub.ingestion.domain;

/**
 * Il file caricato non si può importare nel suo insieme (codifica, formato, intestazione CSV): l'API lo rifiuta subito
 * con {@code 422} e il {@code code} indicato, senza creare il lavoro (F2-ING-02). Gli errori di una singola riga non sono
 * eccezioni: diventano righe {@code INVALID} del rapporto.
 */
public class ImportFileException extends RuntimeException {

    private final String code;

    public ImportFileException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
