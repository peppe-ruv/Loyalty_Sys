package io.loyaltyhub.member.api;

/** Membro in evidenza per il selettore demo (docs/servizi/member-service.md §3, docs/10 §2). */
public record PersonaView(String memberId, String name, String tier, String story, String avatarSeed) {
}
