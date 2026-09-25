package io.loyaltyhub.member.api;

/**
 * Membro in evidenza per il selettore demo (docs/servizi/member-service.md §3, docs/10 §2). {@code balancePts} (saldo
 * della proiezione) serve alle schede membro del Demo Hub (docs/07 §8: "nome, tier, saldo, storia").
 */
public record PersonaView(String memberId, String name, String tier, String story, String avatarSeed, long balancePts) {
}
