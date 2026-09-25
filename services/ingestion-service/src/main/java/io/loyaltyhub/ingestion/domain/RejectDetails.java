package io.loyaltyhub.ingestion.domain;

/**
 * Testi di {@code reject_detail} della pipeline (docs/servizi/ingestion-service.md §5, mostrati in BO-26): un solo
 * posto per la pipeline e per lo storico demo (§6), così le righe seminate sono identiche a quelle reali.
 */
public final class RejectDetails {

    /** Dettaglio salvato sulle righe {@code DUPLICATE} (BO-26 lo mostra nel dettaglio). */
    public static final String DUPLICATE = "Un evento con la stessa fonte e lo stesso id è già stato accettato.";

    private RejectDetails() {
    }

    public static String sourceDisabled(String sourceCode) {
        return "Fonte sconosciuta o disabilitata: " + sourceCode;
    }

    public static String unknownType(String shortType) {
        return "Tipo azione sconosciuto o disabilitato: " + shortType;
    }

    public static String typeNotAllowed(String sourceCode, String shortType) {
        return "Tipo non ammesso per la fonte " + sourceCode + ": " + shortType;
    }

    public static String invalidTime(String time) {
        return "time fuori finestra (max +5 min, -30 giorni): " + time;
    }

    public static String unmatched(String subject) {
        return "Membro non trovato per subject " + subject;
    }

    public static String memberNotActive(String status) {
        return "Membro non attivo (" + status + ")";
    }
}
