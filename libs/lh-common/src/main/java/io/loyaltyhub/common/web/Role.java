package io.loyaltyhub.common.web;

/**
 * Ruoli dell'attore (docs/06 §3). {@code ANALYST} è di sola lettura. {@code SOURCE} è un ruolo di integrazione (Q-492,
 * M8.2f): è l'utenza di servizio di una fonte di ingestion, mai una persona del backoffice, e arriva solo agli
 * endpoint di ingresso che lo elencano; la regola «scrittura» ({@code @RequiresRole} vuoto) non lo include.
 */
public enum Role {
    ANALYST,
    CARE,
    MARKETING,
    LEGAL,
    ADMIN,
    SOURCE;

    public static Role fromString(String s) {
        if (s == null) {
            return ANALYST;
        }
        try {
            return Role.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ANALYST;
        }
    }

    /** Sola lettura: non può eseguire scritture (docs/06 §3). */
    public boolean isReadOnly() {
        return this == ANALYST;
    }

    /** Ruolo di integrazione (utenza di servizio di una fonte), non una persona del backoffice (Q-492). */
    public boolean isIntegration() {
        return this == SOURCE;
    }
}
