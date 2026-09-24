package io.loyaltyhub.member.domain;

import io.loyaltyhub.common.privacy.PersonalData;

/**
 * Regola di anonimizzazione (F-MBR-05, docs/03 §2): "sostituisce nome → 'Membro anonimo', e-mail/telefono → null,
 * conserva id, movimenti e statistiche". Pura: dal membro produce la sua versione anonimizzata, stato
 * {@link MemberStatus#ANONYMIZED} (irreversibile).
 * <p>Si azzerano tutti i campi che identificano o descrivono la persona: nome, cognome, e-mail, telefono, data di
 * nascita, genere, città, identificativo esterno, consensi, attributi personalizzati, seme dell'avatar.
 * Restano: {@code id}, canale e data d'iscrizione, codice amico e legame d'invito (sono identificativi interni,
 * non dati della persona), etichette, completamento del profilo (statistica), versione.
 */
public final class Anonymization {

    /** Nome mostrato al posto di quello reale (docs/03 §2, docs/08 BO-03). */
    public static final String PLACEHOLDER = PersonalData.PLACEHOLDER;

    private Anonymization() {
    }

    // SPEC-GAP: Q-120 — docs/03 dice "nome → 'Membro anonimo'" senza dire in quale campo: il segnaposto va nel
    // `nickname` (che viaggia nei fatti e alimenta le classifiche), `firstName`/`lastName` diventano null, così nessun
    // servizio conserva un nome finto nei campi anagrafici e `displayName()` restituisce "Membro anonimo".
    // SPEC-GAP: Q-121 — attributi personalizzati: le definizioni non dicono quali sono personali (es. `city`,
    // `householdSize`): scelta conservativa, si cancellano tutti. Le etichette restano (classificazioni del programma).
    // Anche i consensi si azzerano: non c'è più una persona a cui scrivere.
    public static Member apply(Member m) {
        return new Member(
                m.id(),
                null,                   // externalId: collega il membro a sistemi esterni con i suoi dati
                null,                   // firstName
                null,                   // lastName
                PLACEHOLDER,            // nickname
                null,                   // email
                null,                   // phone
                null,                   // birthDate
                null,                   // gender
                null,                   // city
                MemberStatus.ANONYMIZED,
                m.channel(),
                m.registeredAt(),
                m.referralCode(),
                m.referredBy(),
                m.referralCompletedAt(),
                "{}",                   // consents: nessun consenso
                "{}",                   // attributes (chiavi interne della demo comprese)
                m.labels(),
                null,                   // avatarSeed
                m.profileCompletedAt(),
                m.version());
    }

    /** {@code true} se {@code confirm} (digitato dall'operatore) coincide con l'id del membro (docs §3). */
    public static boolean confirms(String memberId, String confirm) {
        return memberId != null && confirm != null && memberId.equals(confirm.trim());
    }
}
