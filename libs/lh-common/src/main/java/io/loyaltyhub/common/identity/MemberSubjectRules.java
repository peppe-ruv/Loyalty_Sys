package io.loyaltyhub.common.identity;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Decisione pura della proiezione {@code subjectRef → membro} di ogni servizio del portale (Q-550, ADR-048): dato un
 * fatto {@code member.registered} o {@code member.updated}, lo stato del legame del membro e l'eventuale altro
 * detentore dello stesso pseudonimo, dice che cosa fare. Nessun SQL (regola 19): ogni servizio applica la decisione con
 * le proprie istruzioni costanti, nella stessa transazione dello snapshot.
 *
 * <p>Regole, in ordine di priorità:
 * <ol>
 *   <li>anonimizzazione ({@code PersonalData.isAnonymization}) ⇒ {@link Action#ERASE}: lapide definitiva;</li>
 *   <li>{@code subjectRef} assente ⇒ {@link Action#NONE}: un member-service più vecchio, durante un rilascio
 *       progressivo, non slega nessuno;</li>
 *   <li>legame già cancellato (lapide), oppure fatto più vecchio dell'ultimo aggiornamento del legame ⇒
 *       {@link Action#NONE}: un replay vecchio non ri-lega;</li>
 *   <li>{@code subjectRef: null} ⇒ {@link Action#UNLINK};</li>
 *   <li>{@code subjectRef: r} già legato a un <em>altro</em> membro con istante più recente (o pari e id maggiore) ⇒
 *       {@link Action#NONE}: il fatto è vecchio;</li>
 *   <li>altrimenti ⇒ {@link Action#LINK}: {@code r} si toglie all'altro membro e si assegna a questo nella stessa
 *       transazione ({@link Decision#relink()} dice se c'è il sorpasso, da contare in
 *       {@code lh_member_subject_relinked_total}).</li>
 * </ol>
 * «Vince il più recente» (invece di scartare entrambi): dopo un'anonimizzazione la stessa persona può registrarsi di
 * nuovo e i fatti del vecchio e del nuovo membro viaggiano su partizioni diverse; il risultato converge da solo. A
 * parità di istante decide l'id del membro (il maggiore), uguale su ogni replica.
 */
public final class MemberSubjectRules {

    /** Che cosa fare del legame del membro. */
    public enum Action {
        /** Nessun effetto. */
        NONE,
        /** {@code subject_ref = NULL}, {@code subject_erased = true}, {@code subject_ref_at = t} (lapide). */
        ERASE,
        /** {@code subject_ref = NULL}, {@code subject_ref_at = t}. */
        UNLINK,
        /** {@code subject_ref = r}, {@code subject_ref_at = t} (e {@code r} tolto all'altro detentore). */
        LINK
    }

    /** Il campo {@code subjectRef} di un fatto: assente, {@code null} esplicito o un valore. */
    public record Claim(Kind kind, String value) {

        /** Forma del campo. */
        public enum Kind {
            ABSENT, NULL, VALUE
        }

        public static Claim absent() {
            return new Claim(Kind.ABSENT, null);
        }

        public static Claim unlink() {
            return new Claim(Kind.NULL, null);
        }

        public static Claim link(String subjectRef) {
            return new Claim(Kind.VALUE, subjectRef);
        }

        /**
         * Il campo {@code subjectRef} del payload di un fatto: assente se manca, se non è una stringa o se non ha la
         * forma di uno pseudonimo (un valore non riconoscibile non lega mai nessuno); {@code null} esplicito ⇒ slega.
         */
        public static Claim of(JsonNode data) {
            if (data == null || !data.has("subjectRef")) {
                return absent();
            }
            JsonNode node = data.get("subjectRef");
            if (node == null || node.isNull()) {
                return unlink();
            }
            if (node.isString() && SubjectRef.isValid(node.asString())) {
                return link(node.asString());
            }
            return absent();
        }
    }

    /**
     * Lo stato del legame del membro nella proiezione.
     *
     * @param erased vero se il legame è stato cancellato per anonimizzazione (lapide definitiva)
     * @param refAt  istante dell'ultimo aggiornamento del legame ({@code subject_ref_at}), o {@code null}
     */
    public record Current(boolean erased, Instant refAt) {

        /** Membro mai legato e non cancellato. */
        public static Current fresh() {
            return new Current(false, null);
        }
    }

    /** Un altro membro che già detiene lo stesso {@code subjectRef}. */
    public record Holder(String memberId, Instant refAt) {
    }

    /**
     * Esito.
     *
     * @param action   che cosa fare
     * @param relink   vero se {@code LINK} toglie lo pseudonimo a un altro membro (il sorpasso)
     */
    public record Decision(Action action, boolean relink) {

        static final Decision NONE = new Decision(Action.NONE, false);
    }

    private MemberSubjectRules() {
    }

    /**
     * @param memberId       il membro del fatto
     * @param at             istante del fatto (business time dell'envelope)
     * @param anonymization  vero se il fatto porta il membro in {@code ANONYMIZED}
     * @param claim          il campo {@code subjectRef} del fatto
     * @param current        lo stato del legame di {@code memberId}
     * @param holder         l'altro membro che già detiene {@code claim}, o {@code null}
     */
    public static Decision decide(String memberId, Instant at, boolean anonymization, Claim claim, Current current,
                                  Holder holder) {
        if (anonymization) {
            return new Decision(Action.ERASE, false);
        }
        if (claim == null || claim.kind() == Claim.Kind.ABSENT) {
            return Decision.NONE;
        }
        if (current != null && (current.erased()
                || (current.refAt() != null && at != null && at.isBefore(current.refAt())))) {
            return Decision.NONE;
        }
        if (claim.kind() == Claim.Kind.NULL) {
            return new Decision(Action.UNLINK, false);
        }
        if (holder != null && holder.memberId() != null && !holder.memberId().equals(memberId)) {
            if (newer(holder, memberId, at)) {
                return Decision.NONE;
            }
            return new Decision(Action.LINK, true);
        }
        return new Decision(Action.LINK, false);
    }

    /** Vero se il detentore è più recente del fatto: istante successivo, o pari e id maggiore. */
    private static boolean newer(Holder holder, String memberId, Instant at) {
        if (holder.refAt() == null || at == null) {
            return holder.refAt() != null;
        }
        int cmp = holder.refAt().compareTo(at);
        if (cmp != 0) {
            return cmp > 0;
        }
        return holder.memberId().compareTo(memberId) > 0;
    }
}
