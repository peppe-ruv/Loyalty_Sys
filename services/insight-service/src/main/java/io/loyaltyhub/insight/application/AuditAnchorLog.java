package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.domain.AuditAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ancore dell'audit anche nei log della piattaforma, fuori dal database (F2-GRC-07, workaround di TOBE-002 / Q-400):
 * una riga INFO sul logger dedicato {@value #LOGGER} per ogni ancora DAILY e PURGE scritta dal codice di insight. Chi
 * ha solo le credenziali del database non può riscrivere anche i log; l'operatore confronta una riga con
 * {@code GET /v1/audit/verify?service=&seq=&hash=} (docs/servizi/insight-service.md §5). Nessun dato personale:
 * servizio, posizione, hash, tipo e istante. La riga porta i campi sia nel testo ({@code chiave=valore}) sia come
 * coppie chiave-valore per i log strutturati (ECS nel profilo free).
 */
public final class AuditAnchorLog {

    /** Logger dedicato: si può instradare verso un archivio separato senza toccare il resto dei log. */
    public static final String LOGGER = "io.loyaltyhub.audit.anchor";

    private static final Logger log = LoggerFactory.getLogger(LOGGER);

    private AuditAnchorLog() {
    }

    public static void record(AuditAnchor anchor) {
        log.atInfo()
                .addKeyValue("service", anchor.service())
                .addKeyValue("seq", anchor.seq())
                .addKeyValue("entryHash", anchor.entryHash())
                .addKeyValue("kind", anchor.kind())
                .addKeyValue("anchoredAt", String.valueOf(anchor.anchoredAt()))
                .log("audit-anchor service={} seq={} entryHash={} kind={} anchoredAt={}", anchor.service(),
                        anchor.seq(), anchor.entryHash(), anchor.kind(), anchor.anchoredAt());
    }
}
