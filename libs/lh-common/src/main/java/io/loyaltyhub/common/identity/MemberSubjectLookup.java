package io.loyaltyhub.common.identity;

import java.util.Optional;

/**
 * SPI di un modulo del portale per risolvere il membro del token senza chiamate sincrone tra servizi (Q-550, ADR-048,
 * CLAUDE.md regola 3): ogni servizio tiene una proiezione locale {@code subjectRef → memberId} nella propria tabella
 * snapshot, alimentata dai fatti {@code member.registered} e {@code member.updated}; member-service è la fonte
 * autorevole. L'implementazione legge con SQL costante il solo schema del proprio servizio (regola 19).
 *
 * <p>Nell'hub e con {@code LH_ROLE=all} convivono più lookup: {@code MemberPrincipals} sceglie quella del modulo
 * dell'handler, cioè quella col prefisso di package più lungo di {@link #modulePackage()}.
 */
public interface MemberSubjectLookup {

    /** Package radice del modulo, per esempio {@code io.loyaltyhub.wallet}: gli handler sotto di esso usano questa lookup. */
    String modulePackage();

    /** Il membro legato a {@code subjectRef} (64 esadecimali) nella proiezione locale; vuoto se non ancora legato. */
    Optional<String> memberId(String subjectRef);

    /**
     * Vero solo per la fonte autorevole (member-service): un legame assente significa «non registrato»
     * ({@code 404 MEMBER_NOT_REGISTERED}); altrove significa «non ancora arrivato il fatto» ({@code 409
     * MEMBER_NOT_LINKED} con {@code Retry-After}).
     */
    default boolean authoritative() {
        return false;
    }
}
