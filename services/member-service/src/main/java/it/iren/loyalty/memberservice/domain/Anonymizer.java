package it.iren.loyalty.memberservice.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Anonimizzazione (RF-73, GDPR art. 17): il membro passa in ANONYMIZED, etichette e consensi vengono rimossi,
 * l'id IAM è dissociato. Restano, per obbligo di legge, i movimenti del ledger e il registro giocate dei concorsi
 * (DPR 430/2001), che referenziano solo l'id loyalty. Il CRM riceve l'evento MEMBER (status ANONYMIZED) per la propria cancellazione.
 */
public final class Anonymizer {
    private Anonymizer() {}

    public static Member anonymize(Member m, Instant at) {
        return new Member(m.id(), Member.Status.ANONYMIZED, m.enrolledAt(), null, Map.of(), Map.of(), null, null, null);
    }

    /** Estratto portabile (art. 20): profilo, saldi e movimenti sono raccolti da member-service interrogando ledger e read-model. */
    public record Export(Member member, Object balances, Object movements, Object redemptions, Object plays, Instant generatedAt) {}
}
