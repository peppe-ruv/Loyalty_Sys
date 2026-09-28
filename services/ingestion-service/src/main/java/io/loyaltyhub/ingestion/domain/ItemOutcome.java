package io.loyaltyhub.ingestion.domain;

/**
 * Esito di un elemento dell'ingresso batch o di una riga di un import file (F2-ING-01/02, docs/18 §3.6): i quattro
 * esiti della pipeline ({@link InboundStatus}) più {@code INVALID}, l'elemento che non è un evento leggibile (errore
 * di forma: per {@code POST /v1/events} sarebbe un {@code 400}, nulla salvato nel monitor ingressi).
 */
public enum ItemOutcome {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
    UNMATCHED,
    INVALID;

    public static ItemOutcome of(InboundStatus status) {
        return switch (status) {
            case ACCEPTED -> ACCEPTED;
            case DUPLICATE -> DUPLICATE;
            case REJECTED -> REJECTED;
            case UNMATCHED -> UNMATCHED;
        };
    }
}
