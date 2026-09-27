package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.application.ImportService;
import io.loyaltyhub.ingestion.application.ImportWorker;
import io.loyaltyhub.ingestion.domain.ImportJob;
import io.loyaltyhub.ingestion.domain.ImportRowResult;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Import file asincrono con rapporto (F2-ING-02, BO-32, docs/18 §3.6; docs/servizi/ingestion-service.md §3).
 * Caricamento e «Riprova non abbinati» sono della capacità {@code inbound.handle} (ADMIN, CARE: SPEC-GAP Q-372);
 * lettura per tutti (docs/08 §2).
 */
@RestController
@RequestMapping("/v1/imports")
public class ImportsController {

    /** Header facoltativo: stessa chiave → stesso lavoro, nessun doppio import (Q-353, obbligatorio da M8.10). */
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final MediaType REPORT_TYPE = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final ImportService imports;
    private final ImportWorker worker;

    public ImportsController(ImportService imports, ImportWorker worker) {
        this.imports = imports;
        this.worker = worker;
    }

    /**
     * Carica un file (multipart, parte {@code file}) e lo mette in coda: {@code 202} con il lavoro in {@code QUEUED}.
     * Errori dell'intero file → {@code 422} ({@code IMPORT_EMPTY, IMPORT_FILE_TOO_LARGE, IMPORT_FORMAT_UNSUPPORTED,
     * IMPORT_FORMAT_MISMATCH, IMPORT_NOT_TEXT, IMPORT_INVALID, IMPORT_TOO_MANY_ROWS, IMPORT_KIND_NOT_SUPPORTED,
     * IDEMPOTENCY_KEY_REUSED}).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresRole({Role.ADMIN, Role.CARE})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_ROLE: servono ADMIN o CARE",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "File oltre il tetto del contenitore (2 MB)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "IMPORT_EMPTY, IMPORT_FILE_TOO_LARGE, IMPORT_FORMAT_UNSUPPORTED, "
            + "IMPORT_FORMAT_MISMATCH, IMPORT_NOT_TEXT, IMPORT_INVALID, IMPORT_TOO_MANY_ROWS, IMPORT_KIND_NOT_SUPPORTED, "
            + "IDEMPOTENCY_KEY_REUSED",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ImportJob> create(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "kind", required = false) String kind,
            @RequestPart(value = "source", required = false) String source,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        if (file == null) {
            throw LhException.validation("IMPORT_EMPTY", "Allega un file (parte «file» del modulo).",
                    List.of(new LhException.FieldError("file", "obbligatorio")));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw LhException.badRequest("File non leggibile");
        }
        ImportJob job = imports.create(new ImportService.Upload(file.getOriginalFilename(), file.getContentType(), bytes,
                kind, source, idempotencyKey));
        worker.kick();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    /** Elenco paginato ({@code {items, page}}, docs/06 §2), più recenti prima; filtro {@code status}. */
    @GetMapping
    public PageResponse<ImportJob> list(@RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return imports.list(status, page, size);
    }

    /** Dettaglio: il lavoro e le righe {@code UNMATCHED} ancora da abbinare nel monitor ingressi. */
    @GetMapping("/{id}")
    public ImportService.ImportDetail get(@PathVariable String id) {
        return imports.get(importId(id));
    }

    /** Rapporto per riga (solo le righe non accettate), in ordine di riga; filtro {@code outcome}. */
    @GetMapping("/{id}/rows")
    public PageResponse<ImportRowResult> rows(@PathVariable String id,
                                              @RequestParam(required = false) String outcome,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return imports.rows(importId(id), outcome, page, size);
    }

    /**
     * Rapporto esiti scaricabile in CSV: ogni cella è neutralizzata come formula e quotata ({@code ReportCsv}), nessun
     * dato personale. Il corpo è {@code byte[]} con tipo esplicito {@code text/csv;charset=UTF-8}: il convertitore dei
     * byte non negozia il tipo con l'{@code Accept} della richiesta (una {@code String} passerebbe dal convertitore
     * testuale, che accetta ogni {@code text/*}), quindi la risposta non può diventare {@code text/html}. Il nome del
     * file viene dall'id già validato, attraverso {@link ContentDisposition}; {@code nosniff} impedisce al browser di
     * reinterpretare il tipo.
     */
    @GetMapping("/{id}/report.csv")
    @ApiResponse(responseCode = "200", description = "CSV con intestazione riga, linea, id_evento, esito, codice, "
            + "dettaglio, esito_attuale", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
    public ResponseEntity<byte[]> report(@PathVariable String id) {
        String jobId = importId(id);
        byte[] csv = imports.reportCsv(jobId).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(REPORT_TYPE);
        headers.setContentDisposition(ContentDisposition.attachment().filename("import-" + jobId + "-esiti.csv").build());
        headers.set(NOSNIFF_HEADER, "nosniff");
        return ResponseEntity.ok().headers(headers).body(csv);
    }

    /**
     * «Riprova non abbinati» (BO-32): rivaluta le righe del monitor di questo import ancora {@code UNMATCHED}, a blocchi
     * dopo la riga {@code afterRow} (default 0); si continua con {@code nextAfterRow} finché non è nullo.
     */
    @PostMapping("/{id}/retry-unmatched")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public ImportService.RetryResult retryUnmatched(@PathVariable String id,
                                                    @RequestParam(defaultValue = "0") int afterRow) {
        return imports.retryUnmatched(importId(id), afterRow);
    }

    /**
     * Id di percorso validato al confine: solo un ULID come quelli generati per {@code import_job.id} prosegue verso il
     * servizio e l'intestazione del rapporto; qualunque altra stringa è un {@code 404} RFC 9457 con un messaggio fisso,
     * che non riporta il valore ricevuto.
     */
    private static String importId(String raw) {
        if (!ImportJob.isWellFormedId(raw)) {
            throw LhException.notFound("Import non trovato");
        }
        return raw;
    }
}
