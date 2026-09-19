package io.loyaltyhub.common.web;

/**
 * Attore della richiesta, dall'header {@code X-LH-Actor: <RUOLO>:<username>} (docs/06 §3).
 * Assente ⇒ {@code ANALYST:anonymous} (sola lettura). Disponibile per la richiesta via {@link ActorHolder}.
 */
public record ActorContext(Role role, String username) {

    public static final ActorContext ANONYMOUS = new ActorContext(Role.ANALYST, "anonymous");

    /** Interpreta il valore dell'header; formati non validi ⇒ {@link #ANONYMOUS}. */
    public static ActorContext parse(String header) {
        if (header == null || header.isBlank()) {
            return ANONYMOUS;
        }
        int colon = header.indexOf(':');
        if (colon <= 0 || colon == header.length() - 1) {
            return new ActorContext(Role.fromString(header), "anonymous");
        }
        Role role = Role.fromString(header.substring(0, colon));
        String username = header.substring(colon + 1).trim();
        return new ActorContext(role, username.isBlank() ? "anonymous" : username);
    }

    /** Forma canonica {@code RUOLO:username} usata in audit ed eventi ({@code lhactor}). */
    public String asActorString() {
        return role.name() + ":" + username;
    }
}
