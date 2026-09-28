package io.loyaltyhub.insight.application;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.insight.domain.AuditAnchor;
import io.loyaltyhub.insight.domain.AuditChainHead;
import io.loyaltyhub.insight.domain.AuditChainLink;
import io.loyaltyhub.insight.domain.AuditChainReport;
import io.loyaltyhub.insight.domain.AuditChainWalk;
import io.loyaltyhub.insight.domain.AuditVerification;
import io.loyaltyhub.insight.domain.RedactionLookup;
import io.loyaltyhub.insight.infra.AuditChainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifica della catena di audit (F2-GRC-07, ADR-044, docs/18 §3.15 punto 5): ricalcola in Java, indipendentemente
 * dalle funzioni del database, l'hash di ogni voce e i collegamenti tra voci, e li confronta con testa, ancore e prove
 * delle anonimizzazioni ({@link AuditChainWalk}). Può confrontare la catena anche con un'ancora copiata dai log
 * ({@code audit-anchor}), che vive fuori dal database. È la base di {@code GET /v1/audit/verify} e del futuro
 * {@code lh audit verify} (M12.2).
 * <p>
 * Lettura in {@code REPEATABLE READ}: voci, teste, ancore e prove sono della stessa fotografia, quindi un inserimento o
 * una retention in corso non producono falsi allarmi. Le voci si leggono a pagine (paginazione a chiave su {@code seq}):
 * la memoria usata non cresce con la lunghezza della catena.
 */
@Service
public class AuditChainVerifier {

    static final int PAGE = 500;

    private final AuditChainRepository chain;
    private final Clock clock;

    public AuditChainVerifier(AuditChainRepository chain, Clock clock) {
        this.chain = chain;
        this.clock = clock;
    }

    /** Verifica tutte le catene, o solo quella di {@code service}. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditVerification verify(String service) {
        return verify(service, null);
    }

    /**
     * Verifica tutte le catene, o solo quella di {@code service}, confrontandola anche con {@code expected} (un'ancora
     * copiata dai log, di tipo {@link AuditAnchor#EXTERNAL}; richiede {@code service}).
     *
     * @throws LhException 404 se {@code service} non ha lasciato traccia (né voci, né testa, né ancore) e non è indicata
     *                     un'ancora: con un'ancora dai log, una catena sparita è un esito {@code BROKEN}, non un 404
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditVerification verify(String service, AuditAnchor expected) {
        RedactionLookup redactions = chain.redactions();
        List<AuditChainReport> reports = new ArrayList<>();
        if (service == null) {
            for (String s : chain.services()) {
                reports.add(walk(s, chain.head(s).orElse(null), null, redactions, null));
            }
        } else {
            if (expected == null && !chain.known(service)) {
                throw LhException.notFound("Nessuna catena di audit per il servizio: " + service);
            }
            reports.add(walk(service, chain.head(service).orElse(null), null, redactions, expected));
        }
        return AuditVerification.of(clock.instant(), reports);
    }

    /**
     * Verifica il solo tratto accodato dopo l'ancora {@code lastAnchor} (tutta la catena conservata se {@code null})
     * contro {@code head}, le ancore del servizio e le prove REDACT. Da chiamare dentro una transazione (una fotografia
     * sola): è ciò che il job di ancoraggio verifica prima di registrare una nuova ancora.
     */
    AuditChainReport verifySince(String service, AuditChainHead head, AuditAnchor lastAnchor) {
        return walk(service, head, lastAnchor, chain.redactions(), null);
    }

    private AuditChainReport walk(String service, AuditChainHead head, AuditAnchor startAfter,
                                  RedactionLookup redactions, AuditAnchor expected) {
        AuditChainWalk walk = new AuditChainWalk(service, head, chain.anchors(service), redactions, expected,
                chain.minRetention());
        long cursor = 0;
        if (startAfter != null) {
            walk.startingAfter(startAfter);
            cursor = startAfter.seq();
        }
        while (true) {
            List<AuditChainLink> page = chain.links(service, cursor, PAGE);
            for (AuditChainLink link : page) {
                if (!walk.accept(link)) {
                    return walk.finish();
                }
                cursor = link.seq();
            }
            if (page.size() < PAGE) {
                return walk.finish();
            }
        }
    }
}
