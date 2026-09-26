package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageParams;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.ingestion.domain.ImportFileException;
import io.loyaltyhub.ingestion.domain.ImportFormat;
import io.loyaltyhub.ingestion.domain.ImportJob;
import io.loyaltyhub.ingestion.domain.ImportParser;
import io.loyaltyhub.ingestion.domain.ImportRecord;
import io.loyaltyhub.ingestion.domain.ImportRowResult;
import io.loyaltyhub.ingestion.domain.ItemOutcome;
import io.loyaltyhub.ingestion.domain.ReportCsv;
import io.loyaltyhub.ingestion.infra.ImportRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Import file asincrono con rapporto (F2-ING-02, BO-32, docs/18 §3.6): {@code POST /v1/imports} controlla il file e lo
 * mette in coda ({@code 202}); {@link ImportWorker} lo elabora riga per riga nella pipeline di {@code POST /v1/events}.
 * Controlli sul file (docs/18 §3.10 punto 8, anticipati da M8.10): dimensione massima, estensione e tipo dichiarato in
 * allowlist, contenuto riconosciuto (testo UTF-8 del formato atteso, non l'estensione), numero massimo di righe; gli
 * errori dell'intero file sono {@code 422} con un {@code code} e il lavoro non nasce. Il rapporto scaricabile neutralizza
 * le formule ({@link ReportCsv}) e non contiene dati personali (il soggetto dei non abbinati è omesso).
 * <p>
 * SPEC-GAP: Q-370 — solo {@code kind=EVENTS}; {@code ATTRIBUTES} (punteggi, §3.16) arriva con M13.5 →
 * {@code 422 IMPORT_KIND_NOT_SUPPORTED}; solo upload multipart (niente S3: nessuna infrastruttura nuova). SPEC-GAP: Q-371
 * — limiti di default (1 MiB, 10 000 righe), formati e colonne CSV; Q-353 — {@code Idempotency-Key} facoltativa finché
 * M8.10 non la rende obbligatoria.
 */
@Service
public class ImportService {

    /** Voce di audit (docs/05 §6). */
    static final String ENTITY_TYPE = "import";
    private static final Pattern SOURCE_CODE = Pattern.compile("^[a-z][a-z0-9-]{1,39}$");
    private static final int MAX_KEY_LENGTH = 200;
    static final int MAX_DETAIL = 500;
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final String UNMATCHED_EXPORT_DETAIL = "Soggetto non abbinato a un membro (vedi il monitor ingressi)";

    /** Richiesta di import dal controller. */
    public record Upload(String originalFileName, String contentType, byte[] bytes, String kind, String source,
                         String idempotencyKey) {
    }

    /** Dettaglio: il lavoro più quante righe {@code UNMATCHED} sono ancora da abbinare nel monitor ingressi. */
    public record ImportDetail(ImportJob job, long openUnmatched) {
    }

    /**
     * Esito di «Riprova non abbinati»; {@code failed} = righe non riprovate per un errore imprevisto (restano da
     * abbinare); {@code nextAfterRow} non nullo = restano righe dopo quella riga.
     */
    public record RetryResult(int retried, int accepted, int stillUnmatched, int rejected, int failed,
                              Integer nextAfterRow) {
    }

    private final ImportRepository imports;
    private final SourceRepository sources;
    private final ImportFieldTypes fieldTypes;
    private final InboundResolutionService resolution;
    private final AuditPublisher audit;
    private final ImportParser parser;
    private final Clock clock;
    private final int maxBytes;
    private final int maxRows;
    /** Righe riprovate per chiamata di «Riprova non abbinati»: ognuna è una transazione, la richiesta resta breve. */
    private final int retryBatch;

    public ImportService(ImportRepository imports, SourceRepository sources, ImportFieldTypes fieldTypes,
                         InboundResolutionService resolution, AuditPublisher audit, ObjectMapper mapper, Clock clock,
                         @Value("${loyaltyhub.ingestion.imports.max-bytes:1048576}") int maxBytes,
                         @Value("${loyaltyhub.ingestion.imports.max-rows:10000}") int maxRows,
                         @Value("${loyaltyhub.ingestion.imports.retry-batch:200}") int retryBatch) {
        this.retryBatch = Math.max(1, retryBatch);
        this.imports = imports;
        this.sources = sources;
        this.fieldTypes = fieldTypes;
        this.resolution = resolution;
        this.audit = audit;
        this.parser = new ImportParser(mapper);
        this.clock = clock;
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
    }

    // ================= creazione =================

    /**
     * Controlla il file e mette in coda il lavoro (audit {@code CREATE} con conteggi, mai il contenuto). Con una
     * {@code Idempotency-Key} già vista restituisce il lavoro esistente senza crearne un altro.
     */
    @Transactional
    public ImportJob create(Upload u) {
        String kind = u.kind() == null || u.kind().isBlank() ? ImportJob.KIND_EVENTS : u.kind().trim().toUpperCase(Locale.ROOT);
        if (ImportJob.KIND_ATTRIBUTES.equals(kind)) {
            // SPEC-GAP: Q-370 — docs/18 §3.16 punto 2: import degli attributi (punteggi) con M13.5, proprietario member-service.
            throw LhException.validation("IMPORT_KIND_NOT_SUPPORTED",
                    "L'import di attributi dei membri non è ancora disponibile (M13.5): qui si importano eventi (kind=EVENTS).");
        }
        if (!ImportJob.KIND_EVENTS.equals(kind)) {
            throw LhException.validation("IMPORT_INVALID", "Tipo di import non valido.",
                    List.of(new LhException.FieldError("kind", "ammesso: EVENTS")));
        }
        String actor = ActorHolder.get().asActorString();
        byte[] bytes = u.bytes() == null ? new byte[0] : u.bytes();
        String sha256 = ImportParser.sha256(bytes);
        String key = idempotencyKey(u.idempotencyKey());
        if (key != null) {
            Optional<ImportJob> existing = imports.findByIdempotencyKey(actor, key);
            if (existing.isPresent()) {
                return sameRequest(existing.get(), sha256, kind, sourceCodeOf(u.source()));
            }
        }
        if (bytes.length == 0) {
            throw LhException.validation("IMPORT_EMPTY", "Il file è vuoto.",
                    List.of(new LhException.FieldError("file", "obbligatorio, non vuoto")));
        }
        if (bytes.length > maxBytes) {
            throw LhException.validation("IMPORT_FILE_TOO_LARGE", "Il file supera il limite di " + human(maxBytes)
                    + " (" + human(bytes.length) + "): dividilo in più import.");
        }
        String fileName = ImportParser.sanitizeFileName(u.originalFileName());
        ImportFormat format = ImportFormat.fromFileName(fileName).orElseThrow(() -> LhException.validation(
                "IMPORT_FORMAT_UNSUPPORTED", "Formato non ammesso: carica un file .csv, .ndjson, .jsonl o .json."));
        if (!format.acceptsContentType(u.contentType())) {
            throw LhException.validation("IMPORT_FORMAT_UNSUPPORTED",
                    "Il tipo del file dichiarato dal browser non corrisponde a un file " + format.name() + ".");
        }
        String source = defaultSource(u.source());
        String sourceUrn = source == null ? null : LhSource.source(source);

        // Una lettura completa a vuoto (nessun record trattenuto) valida il file e conta le righe; si ferma appena
        // supera il limite. Gli errori delle singole righe restano per il lavoratore (rapporto).
        String text;
        int rows;
        try {
            text = ImportParser.decode(bytes);
            if (text.isBlank()) {
                throw new ImportFileException("IMPORT_EMPTY", "Il file non contiene righe.");
            }
            rows = parser.read(format, text, sourceUrn, fieldTypes, new ImportParser.Sink() {
                private int seen;

                @Override
                public boolean accept(ImportRecord record) {
                    return ++seen <= maxRows;
                }
            });
        } catch (ImportFileException e) {
            throw LhException.validation(e.code(), e.getMessage());
        }
        if (rows == 0) {
            throw LhException.validation("IMPORT_EMPTY", "Il file non contiene righe di dati.");
        }
        if (rows > maxRows) {
            throw LhException.validation("IMPORT_TOO_MANY_ROWS", "Al massimo " + maxRows
                    + " righe per import: il file ne ha di più. Dividilo in più import.");
        }

        String id = Ulid.next(clock);
        boolean inserted = imports.insert(new ImportRepository.NewJob(id, kind, format.name(), fileName, bytes.length,
                sha256, source, rows, text, key, actor, clock.instant()));
        if (!inserted) {
            // Stessa Idempotency-Key dello stesso autore arrivata in parallelo: vale il primo lavoro, se è la stessa
            // richiesta (l'inserimento ha atteso il suo commit, quindi la lettura lo vede).
            return sameRequest(imports.findByIdempotencyKey(actor, key).orElseThrow(), sha256, kind, source);
        }
        ImportJob job = imports.findById(id).orElseThrow();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("kind", kind);
        after.put("format", format.name());
        after.put("fileName", fileName);
        after.put("sizeBytes", bytes.length);
        after.put("sha256", job.sha256());
        after.put("rows", rows);
        after.put("defaultSource", source);
        after.put("status", job.status().name());
        audit.record(ENTITY_TYPE, id, AuditEntry.Action.CREATE,
                "Caricato l'import " + fileName + " (" + rows + " righe, " + format.name() + ")", null, after);
        return job;
    }

    /**
     * Una {@code Idempotency-Key} già usata dallo stesso autore vale solo per la stessa richiesta (stesso file, tipo e
     * fonte predefinita): allora restituisce il lavoro esistente; altrimenti {@code 422 IDEMPOTENCY_KEY_REUSED}, mai
     * il vecchio lavoro in silenzio al posto del nuovo file.
     */
    private static ImportJob sameRequest(ImportJob existing, String sha256, String kind, String source) {
        if (existing.sha256().equals(sha256) && existing.kind().equals(kind)
                && Objects.equals(existing.defaultSource(), source)) {
            return existing;
        }
        throw LhException.validation("IDEMPOTENCY_KEY_REUSED",
                "Questa Idempotency-Key è già stata usata per un altro import (file, tipo o fonte diversi): usa una chiave nuova.");
    }

    /** Codice della fonte predefinita come arriva (URN o codice), senza verificarne l'esistenza. */
    private static String sourceCodeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim();
        return code.startsWith(LhSource.SOURCE_PREFIX) ? code.substring(LhSource.SOURCE_PREFIX.length()) : code;
    }

    private String defaultSource(String raw) {
        String code = sourceCodeOf(raw);
        if (code == null) {
            return null;
        }
        if (!SOURCE_CODE.matcher(code).matches() || sources.findByCode(code).isEmpty()) {
            throw LhException.validation("IMPORT_INVALID", "Fonte predefinita inesistente.",
                    List.of(new LhException.FieldError("source", "fonte inesistente")));
        }
        return code;
    }

    private static String idempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw LhException.badRequest("Idempotency-Key troppo lunga (max " + MAX_KEY_LENGTH + " caratteri)");
        }
        return key;
    }

    // ================= lettura =================

    public PageResponse<ImportJob> list(String status, int page, int size) {
        String filter = null;
        if (status != null && !status.isBlank()) {
            filter = status.trim().toUpperCase(Locale.ROOT);
            try {
                ImportJob.Status.valueOf(filter);
            } catch (IllegalArgumentException e) {
                throw LhException.badRequest("Parametro status non valido: " + status);
            }
        }
        PageParams p = PageParams.of(page, size);
        return PageResponse.of(imports.page(filter, p.size(), p.offset()), p.page(), p.size(), imports.count(filter));
    }

    public ImportDetail get(String id) {
        ImportJob job = job(id);
        return new ImportDetail(job, imports.countOpenUnmatched(id));
    }

    public PageResponse<ImportRowResult> rows(String id, String outcome, int page, int size) {
        job(id);
        String filter = outcome(outcome);
        PageParams p = PageParams.of(page, size);
        return PageResponse.of(imports.rows(id, filter, p.size(), p.offset()), p.page(), p.size(),
                imports.countRows(id, filter));
    }

    /**
     * Rapporto esiti in CSV (righe non accettate), con le celle neutralizzate come formule. Il dettaglio dei
     * {@code UNMATCHED} è un testo fisso: il soggetto (e-mail, codice cliente) non esce dal servizio.
     */
    public String reportCsv(String id) {
        job(id);
        StringBuilder csv = new StringBuilder(ReportCsv.line(List.of("riga", "linea", "id_evento", "esito", "codice",
                "dettaglio", "esito_attuale")));
        imports.forEachRow(id, r -> {
            String detail = r.outcome() == ItemOutcome.UNMATCHED ? UNMATCHED_EXPORT_DETAIL : r.detail();
            csv.append(ReportCsv.line(Arrays.asList(String.valueOf(r.rowNumber()),
                    r.lineNumber() == null ? null : String.valueOf(r.lineNumber()), r.eventId(), r.outcome().name(),
                    r.rejectCode(), detail, r.currentStatus())));
        });
        return csv.toString();
    }

    /**
     * «Riprova non abbinati» (BO-32, docs/18 §3.6): rivaluta le righe del monitor ingressi di questo import ancora
     * {@code UNMATCHED}, come <em>Riprova</em> di BO-26 (una transazione e una voce di audit per riga). Al più
     * {@code retry-batch} righe per chiamata, dopo la riga {@code afterRow}, perché la richiesta resti breve:
     * {@code nextAfterRow} è il punto da cui continuare, {@code null} a fine elenco. Una riga già risolta nel frattempo
     * si salta; una riga che fallisce per un errore imprevisto è contata in {@code failed} e il blocco prosegue.
     */
    public RetryResult retryUnmatched(String id, int afterRow) {
        job(id);
        int retried = 0;
        int accepted = 0;
        int unmatched = 0;
        int rejected = 0;
        int failed = 0;
        List<ImportRepository.OpenUnmatched> batch = imports.openUnmatched(id, Math.max(afterRow, 0), retryBatch);
        for (ImportRepository.OpenUnmatched row : batch) {
            String status;
            try {
                status = resolution.retry(row.inboundEventId()).status();
            } catch (LhException e) {
                continue; // risolta nel frattempo da un altro operatore o dall'abbinamento automatico
            } catch (RuntimeException e) {
                log.warn("Import {}: riprova della riga {} non riuscita: {}", id, row.rowNumber(), e.toString());
                failed++;
                continue;
            }
            retried++;
            switch (status) {
                case "ACCEPTED" -> accepted++;
                case "UNMATCHED" -> unmatched++;
                default -> rejected++;
            }
        }
        Integer next = batch.size() < retryBatch ? null : batch.getLast().rowNumber();
        return new RetryResult(retried, accepted, unmatched, rejected, failed, next);
    }

    private ImportJob job(String id) {
        return imports.findById(id).orElseThrow(() -> LhException.notFound("Import non trovato: " + id));
    }

    private static String outcome(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String o = raw.trim().toUpperCase(Locale.ROOT);
        try {
            ItemOutcome.valueOf(o);
        } catch (IllegalArgumentException e) {
            throw LhException.badRequest("Parametro outcome non valido: " + raw);
        }
        return o;
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String human(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(Locale.ITALY, "%.1f MB", bytes / 1048576.0)
                : String.format(Locale.ITALY, "%d KB", Math.max(1, bytes / 1024));
    }
}
