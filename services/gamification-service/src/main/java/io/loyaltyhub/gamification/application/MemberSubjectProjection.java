package io.loyaltyhub.gamification.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.identity.MemberSubjectRules;
import io.loyaltyhub.common.metrics.LhMetrics;
import io.loyaltyhub.common.privacy.PersonalData;
import io.loyaltyhub.gamification.infra.MemberSubjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

/**
 * Applica a {@code gamification_member_snapshot} il legame {@code subjectRef → membro} portato dai fatti di member-service
 * ({@code member.registered}, {@code member.updated}, {@code member.status.changed}) secondo {@link MemberSubjectRules}
 * (F2-SEC-09, ADR-048, Q-550). Gira nella transazione del gestore dell'evento (che è già quella idempotente): lo snapshot
 * del membro e il legame nascono o falliscono insieme. Nessuna chiamata sincrona (regola 3), nessun dato personale: solo lo pseudonimo.
 */
@Service
public class MemberSubjectProjection {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "SUSPENDED", "BLOCKED", "CLOSED", "ANONYMIZED");

    private final MemberSubjectRepository subjects;
    private final LhMetrics metrics;

    public MemberSubjectProjection(MemberSubjectRepository subjects, LhMetrics metrics) {
        this.subjects = subjects;
        this.metrics = metrics;
    }

    /**
     * Applica il fatto; {@code memberId} è quello del subject dell'envelope. Idempotente: rigiocarlo non cambia nulla.
     * Concorrenza: due membri che reclamano lo stesso pseudonimo da partizioni diverse non si vedono (nessun detentore da
     * bloccare): il secondo commit viola l'indice unico {@code gamification_member_subject_ref_uq}, l'eccezione è ritentabile
     * (DlqRecords) e al nuovo tentativo le regole si rivalutano con il detentore ormai visibile.
     */
    @Transactional
    public void apply(String memberId, LhEvent<JsonNode> event) {
        boolean anonymization = PersonalData.isAnonymization(event);
        MemberSubjectRules.Claim claim = MemberSubjectRules.Claim.of(event.data());
        if (!anonymization && claim.kind() == MemberSubjectRules.Claim.Kind.ABSENT) {
            return; // un member-service più vecchio non slega nessuno (regola 2 di MemberSubjectRules)
        }
        // Un fatto senza time non vale «adesso»: at resta null e MemberSubjectRules lo tratta con prudenza (salta il controllo
        // di obsolescenza, non sorpassa mai un detentore datato); subject_ref_at non si sposta (Q-550).
        Instant at = event.time();
        subjects.ensureRow(memberId, initialStatus(event.data()));
        MemberSubjectRules.Current current = subjects.lockCurrent(memberId).orElse(MemberSubjectRules.Current.fresh());
        MemberSubjectRules.Holder holder = null;
        if (claim.kind() == MemberSubjectRules.Claim.Kind.VALUE) {
            holder = subjects.lockHolder(claim.value(), memberId).orElse(null);
        }
        MemberSubjectRules.Decision decision = MemberSubjectRules.decide(memberId, at, anonymization, claim, current, holder);
        switch (decision.action()) {
            case ERASE -> subjects.erase(memberId, at);
            case UNLINK -> subjects.unlink(memberId, at);
            case LINK -> {
                if (decision.relink() && holder != null) {
                    subjects.clearRef(holder.memberId());
                    metrics.registry().counter("lh_member_subject_relinked_total").increment();
                }
                subjects.link(memberId, claim.value(), at);
            }
            case NONE -> {
            }
        }
    }

    /** Stato con cui nasce la riga se il fatto arriva prima di ogni altro (di norma la crea {@code member.registered}). */
    private static String initialStatus(JsonNode data) {
        String status = data == null ? "" : data.path("status").asString(data.path("newStatus").asString(""));
        return STATUSES.contains(status) ? status : "ACTIVE";
    }
}
