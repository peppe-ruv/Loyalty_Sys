package io.loyaltyhub.insight.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Ancoraggio dell'audit sempre attivo in {@code enterprise} (F2-GRC-07, ADR-044, CLAUDE.md regole 22 e 24): le ancore
 * nei log sono l'unica prova fuori dal database finché firma ed esportazione immutabile non arrivano (TOBE-002). Il
 * profilo {@code enterprise} rifiuta di partire ({@code INSECURE_CONFIG}) se il job giornaliero è spento
 * ({@code loyaltyhub.insight.audit.anchor-cron=-}) o se il logger {@value AuditAnchorLog#LOGGER} non scrive a livello
 * INFO; negli altri profili resta un avviso. Dove instradare quel logger lo decide chi installa (deploy/README.md).
 */
@Component
@org.springframework.context.annotation.Lazy(false) // controllo all'avvio anche con lazy-initialization (profilo free)
public class AuditAnchorGuard {

    /** Valore di Spring che spegne un {@code @Scheduled(cron = …)}. */
    static final String DISABLED_CRON = "-";

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorGuard.class);

    public AuditAnchorGuard(Environment env,
                            @Value("${loyaltyhub.insight.audit.anchor-cron:0 5 0 * * *}") String anchorCron) {
        check(env, anchorCron, LoggerFactory.getLogger(AuditAnchorLog.LOGGER).isInfoEnabled());
    }

    /** Lancia {@link IllegalStateException} ({@code INSECURE_CONFIG}) nel profilo enterprise se l'ancoraggio è spento. */
    static void check(Environment env, String anchorCron, boolean anchorLogInfoEnabled) {
        boolean cronOff = anchorCron == null || anchorCron.isBlank() || DISABLED_CRON.equals(anchorCron.trim());
        if (!cronOff && anchorLogInfoEnabled) {
            return;
        }
        String problem = cronOff
                ? "loyaltyhub.insight.audit.anchor-cron è spento: nessuna ancora giornaliera dell'audit"
                : "il logger " + AuditAnchorLog.LOGGER + " è sotto INFO: le ancore dell'audit non escono dal database";
        if (env.acceptsProfiles(Profiles.of("enterprise"))) {
            throw new IllegalStateException("INSECURE_CONFIG: " + problem + " (F2-GRC-07, TOBE-002).");
        }
        log.warn("{} (F2-GRC-07): accettato fuori dal profilo enterprise", problem);
    }
}
