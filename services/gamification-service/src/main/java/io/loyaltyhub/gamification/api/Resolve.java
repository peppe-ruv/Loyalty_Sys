package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.web.LhException;

/**
 * Parametro {@code resolve} delle classifiche e dei vincitori (Q-368, ADR-032, F2-EVT-02). Da {@code member.*:2} il
 * soprannome non viaggia più sul bus: con {@code resolve=ids} la risposta porta il {@code memberId} di ogni voce e non
 * il soprannome dello snapshot, e il BFF lo chiede a member-service ({@code POST /v1/members/nicknames}) prima di
 * rispondere al browser. Senza il parametro la risposta resta quella di sempre (compatibilità all'indietro).
 */
final class Resolve {

    /** Unico valore ammesso. */
    static final String IDS = "ids";

    private Resolve() {
    }

    /** {@code true} con {@code resolve=ids}; assente o vuoto → {@code false}; altro valore → {@code 400 BAD_REQUEST}. */
    static boolean ids(String resolve) {
        if (resolve == null || resolve.isBlank()) {
            return false;
        }
        if (IDS.equals(resolve.trim())) {
            return true;
        }
        throw LhException.badRequest("Valore di resolve non ammesso: usare resolve=ids");
    }
}
