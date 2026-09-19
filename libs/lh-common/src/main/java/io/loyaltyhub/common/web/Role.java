package io.loyaltyhub.common.web;

/** Ruoli dell'attore simulato (docs/06 §3). {@code ANALYST} è di sola lettura. */
public enum Role {
    ANALYST,
    CARE,
    MARKETING,
    LEGAL,
    ADMIN;

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
}
