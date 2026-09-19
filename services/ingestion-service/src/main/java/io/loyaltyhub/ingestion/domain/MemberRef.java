package io.loyaltyhub.ingestion.domain;

/** Riga dell'indice membri (docs/servizi/ingestion-service.md §2). */
public record MemberRef(String memberId, String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
