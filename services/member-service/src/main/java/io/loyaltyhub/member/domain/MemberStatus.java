package io.loyaltyhub.member.domain;

/** Stati del ciclo di vita di un membro (docs/servizi/member-service.md §2, docs/03 §2). */
public enum MemberStatus {
    ACTIVE,
    INACTIVE,
    BLOCKED,
    CLOSED,
    ANONYMIZED;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
