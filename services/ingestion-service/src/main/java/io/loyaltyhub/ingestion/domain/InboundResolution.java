package io.loyaltyhub.ingestion.domain;

import java.util.Locale;

/**
 * Regole di risoluzione di un evento in ingresso già registrato (F-ING-04 eventi non abbinati, F-ING-09 <em>riprova</em>;
 * docs/servizi/ingestion-service.md §3, BO-26):
 * <ul>
 *   <li><b>Riprova</b>: solo {@code REJECTED} e {@code UNMATCHED} — rivaluta il payload salvato con la pipeline;</li>
 *   <li><b>Abbina</b>: solo {@code UNMATCHED} — il membro è indicato esplicitamente dall'operatore;</li>
 *   <li><b>Abbinamento automatico</b>: alla registrazione di un membro, i {@code UNMATCHED} parcheggiati il cui
 *       {@code subject} ({@code external:<id>} / {@code email:<x>}) ora risolve al nuovo membro.</li>
 * </ul>
 * {@code ACCEPTED} e {@code DUPLICATE} non si toccano mai: nessuna doppia pubblicazione.
 */
public final class InboundResolution {

    /** Come è stata risolta la riga (colonna {@code inbound_event.resolution}). */
    public enum Kind {
        RETRY, MANUAL_MATCH, AUTO_MATCH
    }

    private InboundResolution() {
    }

    public static boolean canRetry(InboundStatus status) {
        return status == InboundStatus.REJECTED || status == InboundStatus.UNMATCHED;
    }

    public static boolean canMatch(InboundStatus status) {
        return status == InboundStatus.UNMATCHED;
    }

    public static InboundStatus parseStatus(String status) {
        try {
            return status == null ? null : InboundStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Subject cercati dall'abbinamento automatico: {@code null} se il membro non ha quel riferimento. */
    public record AutoMatchKeys(String externalSubject, String emailSubject) {
        public boolean isEmpty() {
            return externalSubject == null && emailSubject == null;
        }
    }

    /**
     * I {@code subject} che, alla registrazione del membro, risolverebbero al nuovo membro secondo il passo 7 della
     * pipeline: {@code external:<externalId>} (confronto esatto, come {@code member_index.external_id}) e
     * {@code email:<email>} (confronto senza maiuscole, come {@code member_index.email_lower}). I {@code member:<id>}
     * non si abbinano automaticamente: l'id lo assegna member-service, un evento parcheggiato con un id inesistente
     * non appartiene a chi lo riceverà in seguito.
     */
    // SPEC-GAP: Q-119 — docs/02 F-ING-04 dice "alla registrazione del membro": solo member.registered (non member.updated
    // che cambia e-mail o externalId) e solo subject external:/email:, mai member:<id>.
    public static AutoMatchKeys autoMatchKeys(String externalId, String email) {
        String external = externalId == null || externalId.isBlank() ? null : "external:" + externalId;
        String mail = email == null || email.isBlank() ? null : "email:" + email.toLowerCase(Locale.ROOT);
        return new AutoMatchKeys(external, mail);
    }
}
