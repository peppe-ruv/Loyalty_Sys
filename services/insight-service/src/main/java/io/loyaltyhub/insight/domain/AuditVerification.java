package io.loyaltyhub.insight.domain;

import java.time.Instant;
import java.util.List;

/**
 * Risposta di {@code GET /v1/audit/verify} (F2-GRC-07): esito complessivo ({@code OK} solo se ogni catena regge) ed
 * esito per servizio. Tutto è letto da una sola fotografia coerente del database.
 */
public record AuditVerification(Instant verifiedAt, AuditChainReport.Status status, List<AuditChainReport> services) {

    public static AuditVerification of(Instant verifiedAt, List<AuditChainReport> services) {
        boolean ok = services.stream().allMatch(AuditChainReport::ok);
        return new AuditVerification(verifiedAt, ok ? AuditChainReport.Status.OK : AuditChainReport.Status.BROKEN,
                List.copyOf(services));
    }
}
