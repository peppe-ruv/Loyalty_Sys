package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.domain.EnvelopeLimits;
import io.loyaltyhub.ingestion.domain.ImportFileException;
import io.loyaltyhub.ingestion.domain.ImportFormat;
import io.loyaltyhub.ingestion.domain.ImportJob;
import io.loyaltyhub.ingestion.domain.ImportParser;
import io.loyaltyhub.ingestion.domain.ImportRecord;
import io.loyaltyhub.ingestion.domain.ItemOutcome;
import io.loyaltyhub.ingestion.infra.ImportRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lavoratore degli import file (F2-ING-02, BO-32): elabora in background, dentro il servizio, i lavori in coda — niente
 * infrastruttura nuova (regola 8). Un solo thread dedicato (non quello dello scheduler, che serve al relay dell'outbox):
 * lo sveglia {@code POST /v1/imports} e, per la ripresa e per le altre istanze, un controllo periodico.
 * <p>
 * Ogni riga è una transazione: pipeline di {@code POST /v1/events} (origine {@code IMPORT}) + esito in
 * {@code import_row} + avanzamento del lavoro solo se è ancora al punto atteso, così un arresto a metà riprende
 * esattamente dalla riga successiva ({@code rows_done}) e due lavoratori sullo stesso lavoro non contano né pubblicano
 * due volte (chi perde annulla la sua transazione, outbox compresa). Una riga che fallisce due volte per un errore
 * imprevisto mentre il database risponde è una riga «velenosa»: si registra {@code INVALID} («errore interno») e il
 * lavoro prosegue. Se non si riesce nemmeno a registrarla (database giù) il lavoro resta {@code RUNNING} e riprende
 * quando il battito è più vecchio di {@code stale-after-ms}; oltre {@code max-attempts} prese in carico diventa
 * {@code FAILED}. A fine lavoro il file è cancellato e resta il rapporto; audit {@code JOB} con i soli conteggi
 * (docs/18 §3.16: «audit con conteggio e non con i valori»).
 */
@Component
@Lazy(false)
public class ImportWorker {

    /** Dettaglio di una riga non elaborabile per un errore interno (niente eccezione né valori nel rapporto). */
    static final String INTERNAL_ERROR_DETAIL = "Errore interno: riga non elaborata; se serve, reinviala in un nuovo import";

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);

    private final ImportRepository imports;
    private final IngestionService ingestion;
    private final ImportFieldTypes fieldTypes;
    private final AuditPublisher audit;
    private final ImportParser parser;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration staleAfter;
    private final int maxAttempts;
    private final Duration retention;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "import-worker");
        t.setDaemon(true);
        return t;
    });

    public ImportWorker(ImportRepository imports, IngestionService ingestion, ImportFieldTypes fieldTypes,
                        AuditPublisher audit, ObjectMapper mapper, PlatformTransactionManager transactionManager,
                        Clock clock,
                        @Value("${loyaltyhub.ingestion.imports.stale-after-ms:120000}") long staleAfterMs,
                        @Value("${loyaltyhub.ingestion.imports.max-attempts:3}") int maxAttempts,
                        @Value("${loyaltyhub.ingestion.imports.retention-days:7}") int retentionDays) {
        this.imports = imports;
        this.ingestion = ingestion;
        this.fieldTypes = fieldTypes;
        this.audit = audit;
        this.parser = new ImportParser(mapper);
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.staleAfter = Duration.ofMillis(staleAfterMs);
        this.maxAttempts = maxAttempts;
        this.retention = Duration.ofDays(retentionDays);
    }

    /** Controllo periodico: lavori in coda arrivati ad altre istanze o fermi da riprendere. */
    @Scheduled(fixedDelayString = "${loyaltyhub.ingestion.imports.poll-interval-ms:5000}",
            initialDelayString = "${loyaltyhub.ingestion.imports.poll-initial-delay-ms:5000}")
    public void poll() {
        kick();
    }

    /** Pulizia oraria: lavori conclusi da più di {@code retention-days} (come {@code inbound_event}, §2). */
    @Scheduled(cron = "${loyaltyhub.ingestion.imports.cleanup-cron:0 40 * * * *}")
    public void cleanup() {
        int deleted = imports.deleteFinishedBefore(clock.instant().minus(retention));
        if (deleted > 0) {
            log.info("Pulizia import: {} lavori conclusi eliminati", deleted);
        }
    }

    /**
     * Sveglia il lavoratore se è fermo; senza effetto se sta già lavorando. Non lancia mai: dopo lo spegnimento il lavoro
     * già salvato resta in coda per un'altra istanza o per il prossimo avvio (un {@code 202} già deciso non diventa
     * {@code 500}).
     */
    public void kick() {
        if (draining.compareAndSet(false, true)) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException e) {
                draining.set(false);
                log.info("Lavoratore import spento: i lavori in coda riprendono al prossimo avvio");
            }
        }
    }

    /** Spegnimento ordinato: interrompe il thread e attende che la riga in corso si chiuda (o si annulli). */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Lavoratore import non terminato entro 10 s: il lavoro in corso riprende dal punto di ripresa");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Instant now = clock.instant();
                Optional<ImportRepository.Claimed> claimed = imports.claim(now, now.minus(staleAfter));
                if (claimed.isEmpty()) {
                    return;
                }
                process(claimed.get());
            }
        } catch (Throwable t) {
            // Nulla si perde: il lavoro resta RUNNING e riprende dal punto di ripresa. Un Error si registra e si rilancia.
            log.error("Lavoratore import interrotto", t);
            if (t instanceof Error error) {
                throw error;
            }
        } finally {
            draining.set(false);
        }
    }

    /** Elabora un lavoro dal punto di ripresa ({@code rowsDone}). */
    private void process(ImportRepository.Claimed job) {
        if (job.attempts() > maxAttempts) {
            fail(job.id(), "Elaborazione interrotta " + (job.attempts() - 1) + " volte: lavoro abbandonato. "
                    + "Le righe già elaborate restano nel rapporto; ricarica il file per le altre (i duplicati sono riconosciuti).");
            return;
        }
        if (job.content() == null) {
            fail(job.id(), "Il file non è più disponibile.");
            return;
        }
        RowRunner runner = new RowRunner(job);
        try {
            String sourceUrn = job.defaultSource() == null ? null : LhSource.source(job.defaultSource());
            parser.read(ImportFormat.valueOf(job.format()), job.content(), sourceUrn, fieldTypes, runner);
        } catch (ImportFileException e) {
            fail(job.id(), "Il file non si può più leggere: " + e.getMessage());
            return;
        }
        if (runner.stopped) {
            return;
        }
        tx.executeWithoutResult(status -> {
            if (imports.finish(job.id(), clock.instant())) {
                imports.findById(job.id()).ifPresent(this::auditFinished);
            }
        });
    }

    /** Esito di una riga: ammessa, lavoro non più nostro, oppure errore imprevisto. */
    private enum Step { ADVANCED, LOST, FAILED }

    /**
     * Riceve i record dal parser uno alla volta: salta quelli già elaborati ({@code rowsDone}) e porta ogni altro nella
     * sua transazione. Si ferma (e il lavoro resta {@code RUNNING}) se il lavoro non è più suo o se non riesce a
     * registrare nemmeno l'esito di una riga velenosa.
     */
    private final class RowRunner implements ImportParser.Sink {
        private final ImportRepository.Claimed job;
        private int index;
        private boolean stopped;

        RowRunner(ImportRepository.Claimed job) {
            this.job = job;
        }

        @Override
        public boolean accept(ImportRecord record) {
            if (Thread.currentThread().isInterrupted()) {
                stopped = true;
                return false;
            }
            int expectedDone = index++;
            if (expectedDone < job.rowsDone()) {
                return true;
            }
            Step step = attempt(() -> processRow(job.id(), expectedDone, record));
            if (step == Step.FAILED) {
                // Seconda prova: un errore transitorio (conflitto, connessione ripresa) passa; uno deterministico no.
                step = attempt(() -> processRow(job.id(), expectedDone, record));
            }
            if (step == Step.FAILED) {
                log.warn("Import {}: riga {} fallita due volte, registrata come non valida", job.id(), record.row());
                step = attempt(() -> recordPoisonRow(job.id(), expectedDone, record));
            }
            if (step != Step.ADVANCED) {
                log.info("Import {}: lavorazione sospesa alla riga {} ({})", job.id(), record.row(), step);
                stopped = true;
                return false;
            }
            return true;
        }

        private Step attempt(RowWork work) {
            try {
                Boolean advanced = tx.execute(status -> {
                    boolean ok = work.run();
                    if (!ok) {
                        status.setRollbackOnly();
                    }
                    return ok;
                });
                return Boolean.TRUE.equals(advanced) ? Step.ADVANCED : Step.LOST;
            } catch (RuntimeException e) {
                log.warn("Import {}: errore sulla riga: {}", job.id(), e.toString());
                return Step.FAILED;
            }
        }
    }

    @FunctionalInterface
    private interface RowWork {
        boolean run();
    }

    /** Una riga: pipeline + esito + avanzamento, nella transazione del chiamante. {@code false} = lavoro non più nostro. */
    private boolean processRow(String importId, int expectedDone, ImportRecord record) {
        ItemOutcome outcome;
        String rejectCode = null;
        String detail = null;
        String inboundId = null;
        if (!record.readable()) {
            outcome = ItemOutcome.INVALID;
            detail = record.error();
        } else {
            InboundEventRequest request = new InboundEventRequest(record.specversion(), record.id(), record.source(),
                    record.type(), record.subject(), record.time(), record.data());
            String formError = formError(request);
            if (formError != null) {
                outcome = ItemOutcome.INVALID;
                detail = formError;
            } else {
                IngestionService.Tracked tracked = ingestion.ingestTracked(request, IngestionService.ORIGIN_IMPORT);
                outcome = ItemOutcome.of(tracked.result().status());
                rejectCode = tracked.result().rejectCode() == null ? null : tracked.result().rejectCode().name();
                // Il dettaglio dei non abbinati contiene il soggetto: resta solo nel monitor (che l'anonimizzazione
                // ripulisce) e il rapporto lo legge da lì.
                detail = outcome == ItemOutcome.UNMATCHED ? null : tracked.detail();
                inboundId = tracked.inboundId();
            }
        }
        return advanceAndRecord(importId, expectedDone, record, outcome, rejectCode, detail, inboundId);
    }

    /** Riga velenosa: nessun ingresso, solo l'esito {@code INVALID} con il motivo generico, e si va avanti. */
    private boolean recordPoisonRow(String importId, int expectedDone, ImportRecord record) {
        return advanceAndRecord(importId, expectedDone, record, ItemOutcome.INVALID, null, INTERNAL_ERROR_DETAIL, null);
    }

    private boolean advanceAndRecord(String importId, int expectedDone, ImportRecord record, ItemOutcome outcome,
                                     String rejectCode, String detail, String inboundId) {
        if (!imports.advance(importId, expectedDone, outcome, clock.instant())) {
            return false;
        }
        if (outcome != ItemOutcome.ACCEPTED) {
            imports.insertRow(importId, new ImportRepository.NewRow(record.row(), record.line(),
                    ImportService.truncate(record.id(), EnvelopeLimits.MAX_ID), outcome, rejectCode,
                    ImportService.truncate(detail, ImportService.MAX_DETAIL), inboundId));
        }
        return true;
    }

    private String formError(InboundEventRequest request) {
        try {
            ingestion.checkExternalForm(request);
            return null;
        } catch (LhException e) {
            return e.getMessage();
        }
    }

    private void fail(String id, String detail) {
        tx.executeWithoutResult(status -> {
            if (imports.fail(id, detail, clock.instant())) {
                imports.findById(id).ifPresent(this::auditFinished);
            }
        });
        log.warn("Import {} fallito: {}", id, detail);
    }

    private void auditFinished(ImportJob job) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", ImportJob.Status.RUNNING.name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", job.status().name());
        after.put("rowsTotal", job.rowsTotal());
        after.put("rowsDone", job.rowsDone());
        after.put("accepted", job.counts().accepted());
        after.put("duplicate", job.counts().duplicate());
        after.put("rejected", job.counts().rejected());
        after.put("unmatched", job.counts().unmatched());
        after.put("invalid", job.counts().invalid());
        String summary = job.status() == ImportJob.Status.DONE
                ? "Import " + job.fileName() + " completato: " + job.counts().accepted() + " accettate, "
                + job.counts().duplicate() + " duplicate, " + job.counts().rejected() + " respinte, "
                + job.counts().unmatched() + " non abbinate, " + job.counts().invalid() + " non valide"
                : "Import " + job.fileName() + " fallito dopo " + job.rowsDone() + " righe su " + job.rowsTotal();
        audit.recordJob(ImportService.ENTITY_TYPE, job.id(), summary, before, after);
    }
}
