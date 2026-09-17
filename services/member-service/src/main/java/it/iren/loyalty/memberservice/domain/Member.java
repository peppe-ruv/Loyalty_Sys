package it.iren.loyalty.memberservice.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Membro del programma (RF-72): identità = sub OIDC (D12), nessun dato anagrafico duplicato dal CRM.
 * Etichette chiave/valore libere (come le "labels" di Open Loyalty) per segmenti e regole; consensi per chiave.
 * Stati: ACTIVE (matura e spende), SUSPENDED (matura ma non spende: es. verifica frodi), CLOSED (nessuna operazione),
 * ANONYMIZED (chiuso e anonimizzato: restano solo ledger e registro giocate per obblighi di legge).
 */
public record Member(
        String id,
        Status status,
        Instant enrolledAt,
        String enrollmentChannel,
        Map<String, String> labels,
        Map<String, Boolean> consents,
        String referralCode,
        String referredBy,
        Instant profileCompletedAt
) {
    public enum Status { ACTIVE, SUSPENDED, CLOSED, ANONYMIZED }

    public boolean canEarn() { return status == Status.ACTIVE || status == Status.SUSPENDED; }
    public boolean canSpend() { return status == Status.ACTIVE; }
}
