package io.loyaltyhub.common.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attore della richiesta, dall'header {@code X-LH-Actor: <RUOLO>:<username>} (docs/06 §3).
 * Assente ⇒ {@code ANALYST:anonymous} (sola lettura). Disponibile per la richiesta via {@link ActorHolder}.
 */
public record ActorContext(Role role, String username) {

    public static final ActorContext ANONYMOUS = new ActorContext(Role.ANALYST, "anonymous");

    /** Forma canonica: ruolo in maiuscolo, un solo {@code :}, username non vuoto e senza spazi ai bordi. */
    private static final Pattern CANONICAL = Pattern.compile("^([A-Z]+):([^:\\s](?:[^:]*[^:\\s])?)$");

    /**
     * Interpreta il valore dell'header. Q-298 DECISA (estende Q-261): solo {@code RUOLO:username} con un ruolo noto in
     * maiuscolo e uno username non vuoto vale il ruolo dichiarato; ogni altra forma (ruolo minuscolo o sconosciuto, spazi
     * ai bordi, ruolo senza {@code :}, {@code RUOLO:} o {@code :username}, più di un {@code :}) vale {@code ANALYST},
     * mai un ruolo di scrittura. Lo username resta, per l'audit, il testo dopo il primo {@code :} (o {@code anonymous}).
     */
    public static ActorContext parse(String header) {
        if (header == null || header.isBlank()) {
            return ANONYMOUS;
        }
        Matcher m = CANONICAL.matcher(header);
        if (m.matches()) {
            Role role = known(m.group(1));
            if (role != null) {
                return new ActorContext(role, m.group(2));
            }
        }
        int colon = header.indexOf(':');
        String username = colon < 0 ? "" : header.substring(colon + 1).trim();
        return new ActorContext(Role.ANALYST, username.isEmpty() ? "anonymous" : username);
    }

    /**
     * Attore da un access token verificato (profilo {@code enterprise}, ADR-027): i ruoli arrivano dal claim
     * {@code lh_roles}. {@code ADMIN} vale se presente; un solo ruolo operatore vale quel ruolo; più ruoli operatore
     * diversi o nessuno valgono {@code ANALYST} (sola lettura), mai l'unione dei poteri.
     */
    // SPEC-GAP: Q-365 — ActorContext ha un solo ruolo; con più ruoli operatore si sceglie la sola lettura.
    public static ActorContext fromToken(java.util.Collection<String> roles, String username) {
        String name = username == null || username.isBlank() ? "anonymous" : username;
        java.util.Set<Role> operator = java.util.EnumSet.noneOf(Role.class);
        for (String r : roles == null ? java.util.List.<String>of() : roles) {
            Role role = r == null ? null : known(r);
            if (role != null) {
                operator.add(role);
            }
        }
        if (operator.contains(Role.ADMIN)) {
            return new ActorContext(Role.ADMIN, name);
        }
        return new ActorContext(operator.size() == 1 ? operator.iterator().next() : Role.ANALYST, name);
    }

    private static Role known(String name) {
        for (Role r : Role.values()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    /** Forma canonica {@code RUOLO:username} usata in audit ed eventi ({@code lhactor}). */
    public String asActorString() {
        return role.name() + ":" + username;
    }
}
