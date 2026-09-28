package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.infra.AuditRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retention (RNF-07, docs/servizi/insight-service.md §5): l'event store tiene gli eventi degli ultimi
 * {@code max-age-days} giorni o le {@code max-rows} righe più recenti (il minore); l'audit 180 giorni, mai meno di
 * {@link AuditRetentionGuard#MIN_DAYS}, tagliando per ogni catena di hash solo la parte iniziale scaduta (F2-GRC-07).
 * Ogni ancora PURGE lasciata dall'audit va anche nei log ({@link AuditAnchorLog}). Le metriche ({@code metric_daily})
 * sono illimitate. Job orario.
 * <p>
 * Una voce scaduta che segue una più recente (per esempio un evento con {@code time} nel futuro) ferma la retention
 * della sua catena finché non scade anche quella: si conserva di più, mai un buco nella catena. Il job lo segnala con
 * un WARN, al più uno al giorno per servizio.
 */
// SPEC-GAP: Q-402 — retention per età di business (`at`) e per prefisso della catena; lo stallo è segnalato, non forzato.
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    /** Al più un avviso di retention ferma per servizio in questo intervallo. */
    static final Duration STALL_WARNING_INTERVAL = Duration.ofDays(1);

    private final EventStoreRepository events;
    private final AuditRepository audits;
    private final int maxAgeDays;
    private final int maxRows;
    private final int auditMaxAgeDays;
    private final Clock clock;
    private final Map<String, Instant> stallWarnedAt = new ConcurrentHashMap<>();

    @Autowired
    public RetentionJob(EventStoreRepository events, AuditRepository audits,
                        @Value("${loyaltyhub.insight.retention.max-age-days:14}") int maxAgeDays,
                        @Value("${loyaltyhub.insight.retention.max-rows:200000}") int maxRows,
                        @Value("${loyaltyhub.insight.retention.audit-max-age-days:180}") int auditMaxAgeDays,
                        Clock clock) {
        this.events = events;
        this.audits = audits;
        this.maxAgeDays = maxAgeDays;
        this.maxRows = maxRows;
        this.auditMaxAgeDays = auditMaxAgeDays;
        this.clock = clock;
    }

    public RetentionJob(EventStoreRepository events, AuditRepository audits, int maxAgeDays, int maxRows,
                        int auditMaxAgeDays) {
        this(events, audits, maxAgeDays, maxRows, auditMaxAgeDays, Clock.systemUTC());
    }

    @Scheduled(cron = "${loyaltyhub.insight.retention.cron:0 20 * * * *}")
    public void purge() {
        int byAge = events.deleteOlderThan(maxAgeDays);
        int byRows = events.trimToMaxRows(maxRows);
        AuditRepository.Purge audit = audits.deleteOlderThan(auditMaxAgeDays);
        audit.anchors().forEach(AuditAnchorLog::record);
        warnStalls();
        if (byAge + byRows + audit.deleted() > 0) {
            log.debug("Retention: event store {} per età (>{}g) + {} per soglia (>{} righe); audit {} (>{}g)",
                    byAge, maxAgeDays, byRows, maxRows, audit.deleted(), auditMaxAgeDays);
        }
    }

    /** Catene la cui retention è ferma dietro una voce più recente: un WARN per servizio, al più uno al giorno. */
    private void warnStalls() {
        List<AuditRepository.RetentionStall> stalls = audits.retentionStalls(auditMaxAgeDays);
        Instant now = clock.instant();
        for (AuditRepository.RetentionStall s : stalls) {
            Instant last = stallWarnedAt.get(s.service());
            if (last != null && now.isBefore(last.plus(STALL_WARNING_INTERVAL))) {
                continue;
            }
            stallWarnedAt.put(s.service(), now);
            log.warn("Retention dell'audit ferma per il servizio {}: {} voci scadute seguono la voce {} (at {}) e restano "
                    + "finché non scade anche quella (Q-402)", s.service(), s.expiredKept(), s.blockingSeq(), s.blockingAt());
        }
    }
}
