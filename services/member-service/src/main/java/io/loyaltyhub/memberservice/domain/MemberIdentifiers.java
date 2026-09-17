package io.loyaltyhub.memberservice.domain;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Identificatori del membro (RF-108): oltre al sub OIDC (ADR-012), email, telefono e tessera fedeltà con configurazione
 * "obbligatorio" (uno solo), "univoco" e "usato per l'abbinamento" degli eventi, in ordine di priorità; generazione
 * automatica della tessera (formato, lunghezza, prefisso). Definizione di "membro attivo" per periodo e tipi azione.
 */
public record MemberIdentifiers(List<Identifier> priority, CardGenerator card, ActiveMemberRule activeRule) {
    public enum Kind { OIDC_SUB, CRM_ID, SAP_BP, EMAIL, PHONE, LOYALTY_CARD }
    public record Identifier(Kind kind, boolean required, boolean unique, boolean matching) {}
    public record CardGenerator(boolean enabled, Format format, int length, String prefix) {
        public enum Format { ALPHANUMERIC, LETTERS, DIGITS }
        private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789", LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ", DIGITS = "0123456789";
        public String generate(SecureRandom rnd) {
            String alphabet = switch (format) { case ALPHANUMERIC -> ALNUM; case LETTERS -> LETTERS; case DIGITS -> DIGITS; };
            StringBuilder sb = new StringBuilder(prefix == null ? "" : prefix);
            for (int i = 0; i < length; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            return sb.toString();
        }
    }
    /** Membro attivo = almeno un'azione dei tipi indicati (vuoto = tutti) negli ultimi N giorni. */
    public record ActiveMemberRule(int days, List<String> actionTypes) {}

    public MemberIdentifiers {
        if (priority.stream().filter(Identifier::required).count() > 1) throw new IllegalArgumentException("only one identifier can be required");
        for (Identifier i : priority) {
            if (i.required() && !i.unique()) throw new IllegalArgumentException(i.kind() + ": required must be unique");
            if (i.matching() && !i.unique()) throw new IllegalArgumentException(i.kind() + ": matching must be unique");
        }
        if (card.enabled() && (card.length() < 4 || card.length() > 64 || (card.prefix() != null && card.prefix().length() > 8))) throw new IllegalArgumentException("card: length 4-64, prefix <= 8");
    }

    /** Abbinamento di un evento (RI-02): prova gli identificatori abilitati nell'ordine configurato. */
    public Optional<Kind> matchOrder(Map<Kind, String> known) {
        return priority.stream().filter(Identifier::matching).map(Identifier::kind).filter(k -> known.get(k) != null && !known.get(k).isBlank()).findFirst();
    }

    public static MemberIdentifiers example() {
        return new MemberIdentifiers(List.of(new Identifier(Kind.OIDC_SUB, true, true, true), new Identifier(Kind.CRM_ID, false, true, true), new Identifier(Kind.SAP_BP, false, true, true),
                new Identifier(Kind.EMAIL, false, true, true), new Identifier(Kind.PHONE, false, false, false), new Identifier(Kind.LOYALTY_CARD, false, true, true)),
                new CardGenerator(true, CardGenerator.Format.ALPHANUMERIC, 8, "LH"), new ActiveMemberRule(365, List.of()));
    }
}
