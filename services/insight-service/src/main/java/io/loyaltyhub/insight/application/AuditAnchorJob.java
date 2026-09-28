package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.domain.AuditAnchor;
import io.loyaltyhub.insight.domain.AuditChainHead;
import io.loyaltyhub.insight.domain.AuditChainReport;
import io.loyaltyhub.insight.infra.AuditChainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Ancoraggio giornaliero della catena di audit (F2-GRC-07, docs/18 §3.15 punto 5): per ogni servizio registra la testa
 * corrente in {@code audit_anchor} (sola inserzione), <em>dopo</em> aver verificato il tratto accodato dall'ultima
 * ancora, e la scrive anche nei log ({@link AuditAnchorLog}), fuori dal database. Una catena che non regge non viene
 * ancorata (l'ancora legittimerebbe l'alterazione): errore nel log, e {@code GET /v1/audit/verify} indica dove. Un
 * servizio senza voci nuove dall'ultima ancora non riceve un'ancora in più; con più repliche l'ancora è una sola
 * (indice unico, {@code ON CONFLICT DO NOTHING}).
 * <p>
 * Firmare le ancore ed esportarle su un archivio immutabile è la seconda parte di F2-GRC-07 (Q-400, TOBE-002). Ogni
 * transazione legge una sola fotografia ({@code REPEATABLE READ}).
 */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class AuditAnchorJob {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorJob.class);

    private final AuditChainRepository chain;
    private final AuditChainVerifier verifier;
    private final TransactionTemplate tx;

    public AuditAnchorJob(AuditChainRepository chain, AuditChainVerifier verifier, PlatformTransactionManager txManager) {
        this.chain = chain;
        this.verifier = verifier;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Scheduled(cron = "${loyaltyhub.insight.audit.anchor-cron:0 5 0 * * *}", zone = "UTC")
    public void anchorDaily() {
        List<AuditAnchor> anchored = anchor();
        if (!anchored.isEmpty()) {
            log.info("Audit: ancorate {} catene", anchored.size());
        }
    }

    /**
     * Ancora la testa di ogni catena cresciuta dall'ultima ancora e verificata; ogni ancora scritta va anche nei log.
     *
     * @return le ancore registrate
     */
    public List<AuditAnchor> anchor() {
        List<AuditAnchor> anchored = new ArrayList<>();
        for (AuditChainHead listed : chain.heads()) {
            Optional<AuditAnchor> done = tx.execute(status -> anchorOne(listed.service()));
            if (done != null && done.isPresent()) {
                AuditAnchorLog.record(done.get());
                anchored.add(done.get());
            }
        }
        return anchored;
    }

    private Optional<AuditAnchor> anchorOne(String service) {
        AuditChainHead head = chain.head(service).orElse(null);
        if (head == null || head.seq() == 0) {
            return Optional.empty();
        }
        AuditAnchor last = chain.anchors(service).stream().max(Comparator.comparingLong(AuditAnchor::seq)).orElse(null);
        if (last != null && last.seq() >= head.seq()) {
            return Optional.empty(); // nessuna voce nuova (la verifica completa resta quella su richiesta)
        }
        AuditChainReport report = verifier.verifySince(service, head, last);
        if (!report.ok()) {
            log.error("Audit: catena {} interrotta alla voce {} ({}), non ancorata: {}", service, report.brokenSeq(),
                    report.reason(), report.detail());
            return Optional.empty();
        }
        return chain.insertAnchor(head, AuditAnchor.DAILY);
    }
}
