package io.loyaltyhub.insight.application;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.insight.domain.AuditChainHead;
import io.loyaltyhub.insight.domain.AuditChainLink;
import io.loyaltyhub.insight.domain.AuditChainReport;
import io.loyaltyhub.insight.domain.AuditChainWalk;
import io.loyaltyhub.insight.domain.AuditVerification;
import io.loyaltyhub.insight.infra.AuditChainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifica della catena di audit (F2-GRC-07, ADR-044, docs/18 §3.15 punto 5): ricalcola in Java, indipendentemente
 * dalle funzioni del database, l'hash di ogni voce e i collegamenti tra voci, e li confronta con testa e ancore
 * ({@link AuditChainWalk}). È la base di {@code GET /v1/audit/verify} e del futuro {@code lh audit verify} (M12.2).
 * <p>
 * Lettura in {@code REPEATABLE READ}: voci, teste e ancore sono della stessa fotografia, quindi un inserimento o una
 * retention in corso non producono falsi allarmi. Le voci si leggono a pagine (paginazione a chiave su {@code seq}):
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

    /**
     * Verifica tutte le catene, o solo quella di {@code service}.
     *
     * @throws LhException 404 se {@code service} non ha né voci né testa
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditVerification verify(String service) {
        List<AuditChainReport> reports = new ArrayList<>();
        if (service == null) {
            for (String s : chain.services()) {
                reports.add(verifyFrom(s, chain.head(s).orElse(null), 0));
            }
        } else {
            AuditChainHead head = chain.head(service).orElse(null);
            if (head == null && !chain.hasEntries(service)) {
                throw LhException.notFound("Nessuna catena di audit per il servizio: " + service);
            }
            reports.add(verifyFrom(service, head, 0));
        }
        return AuditVerification.of(clock.instant(), reports);
    }

    /**
     * Verifica le voci del servizio con {@code seq > afterSeq} (0 = tutta la catena conservata) contro {@code head} e
     * le ancore del servizio. Da chiamare dentro una transazione (una fotografia sola): il job di ancoraggio la usa per
     * il solo tratto successivo all'ultima ancora.
     */
    AuditChainReport verifyFrom(String service, AuditChainHead head, long afterSeq) {
        AuditChainWalk walk = new AuditChainWalk(service, head, chain.anchors(service));
        long cursor = afterSeq;
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
