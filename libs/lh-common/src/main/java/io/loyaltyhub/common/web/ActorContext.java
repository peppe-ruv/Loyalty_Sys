package io.loyaltyhub.common.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attore della richiesta, dall'header {@code X-LH-Actor: <RUOLO>:<username>} (docs/06 §3); per una fonte
 * {@code SOURCE:<client-id>} (per esempio {@code SOURCE:src-crm}, Q-492).
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

    /** Prefisso del client di una fonte: il client della fonte {@code <codice>} è {@code src-<codice>} (Q-492). */
    public static final String SOURCE_CLIENT_PREFIX = "src-";

    /**
     * Attore da un access token verificato (profilo {@code enterprise}, ADR-027): i ruoli arrivano dal claim
     * {@code lh_roles}. {@code ADMIN} vale se presente; un solo ruolo operatore vale quel ruolo; più ruoli operatore
     * diversi valgono {@code ANALYST} (sola lettura), mai l'unione dei poteri. {@code SOURCE} (utenza di integrazione
     * di una fonte, Q-492) non è un ruolo operatore e non escalata mai: vale solo se è l'unico ruolo noto, e allora il
     * nome dell'attore è il client del token ({@code azp}, poi {@code client_id}), non lo username dell'utenza di
     * servizio; con altri ruoli operatore valgono questi. Nessun ruolo noto vale {@code ANALYST}.
     *
     * @param username nome leggibile per audit e log (di solito {@code preferred_username})
     * @param clientId client del token ({@code azp} o {@code client_id}), usato come nome dell'attore {@code SOURCE}
     */
    // SPEC-GAP: Q-365 — ActorContext ha un solo ruolo; con più ruoli operatore si sceglie la sola lettura.
    public static ActorContext fromToken(java.util.Collection<String> roles, String username, String clientId) {
        String name = username == null || username.isBlank() ? "anonymous" : username;
        java.util.Set<Role> operator = java.util.EnumSet.noneOf(Role.class);
        boolean source = false;
        for (String r : roles == null ? java.util.List.<String>of() : roles) {
            Role role = r == null ? null : known(r);
            if (role == Role.SOURCE) {
                source = true;
            } else if (role != null) {
                operator.add(role);
            }
        }
        if (operator.contains(Role.ADMIN)) {
            return new ActorContext(Role.ADMIN, name);
        }
        if (operator.isEmpty() && source) {
            return new ActorContext(Role.SOURCE, clientId == null || clientId.isBlank() ? "anonymous" : clientId);
        }
        return new ActorContext(operator.size() == 1 ? operator.iterator().next() : Role.ANALYST, name);
    }

    /**
     * Codice della fonte dell'attore {@code SOURCE}: il client id senza il prefisso {@code src-}. Vuoto per un ruolo
     * diverso da {@code SOURCE}, per un client senza prefisso o per il solo prefisso: nessuna fonte è consentita.
     */
    public java.util.Optional<String> sourceCode() {
        if (role != Role.SOURCE || !username.startsWith(SOURCE_CLIENT_PREFIX)
                || username.length() == SOURCE_CLIENT_PREFIX.length()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(username.substring(SOURCE_CLIENT_PREFIX.length()));
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
