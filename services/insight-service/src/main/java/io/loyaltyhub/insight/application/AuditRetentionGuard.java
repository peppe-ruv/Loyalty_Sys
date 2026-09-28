package io.loyaltyhub.insight.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Età minima dell'audit (F2-GRC-07, ADR-043, ADR-044, CLAUDE.md regola 22): la retention non cancella mai voci più
 * giovani di {@link #MIN_DAYS} giorni. Il database lo impone comunque ({@code audit_purge_before} limita la soglia con
 * {@code audit_min_retention_days()}, stesso valore); qui si rifiuta la configurazione che lo chiederebbe. Nel profilo
 * {@code enterprise} una {@code loyaltyhub.insight.retention.audit-max-age-days} più corta ferma l'avvio
 * ({@code INSECURE_CONFIG}); negli altri profili resta un avviso, perché il valore efficace è comunque il minimo.
 */
@Component
@org.springframework.context.annotation.Lazy(false) // controllo all'avvio anche con lazy-initialization (profilo free)
public class AuditRetentionGuard {

    /** Stesso valore di {@code audit_min_retention_days()} (V6). 365 quando arriva la retention di ADR-043 (M8.12). */
    public static final int MIN_DAYS = 180;

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionGuard.class);

    public AuditRetentionGuard(Environment env,
                               @Value("${loyaltyhub.insight.retention.audit-max-age-days:180}") int auditMaxAgeDays) {
        check(env, auditMaxAgeDays);
    }

    /** Lancia {@link IllegalStateException} ({@code INSECURE_CONFIG}) nel profilo enterprise con una retention corta. */
    static void check(Environment env, int auditMaxAgeDays) {
        if (auditMaxAgeDays >= MIN_DAYS) {
            return;
        }
        if (env.acceptsProfiles(Profiles.of("enterprise"))) {
            throw new IllegalStateException("INSECURE_CONFIG: loyaltyhub.insight.retention.audit-max-age-days="
                    + auditMaxAgeDays + " è sotto l'età minima dell'audit (" + MIN_DAYS + " giorni, ADR-043).");
        }
        log.warn("loyaltyhub.insight.retention.audit-max-age-days={} è sotto l'età minima dell'audit: il database "
                + "conserva comunque {} giorni", auditMaxAgeDays, MIN_DAYS);
    }
}
