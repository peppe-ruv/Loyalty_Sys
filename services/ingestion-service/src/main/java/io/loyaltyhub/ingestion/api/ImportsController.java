package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.application.ImportService;
import io.loyaltyhub.ingestion.application.ImportWorker;
import io.loyaltyhub.ingestion.domain.ImportJob;
import io.loyaltyhub.ingestion.domain.ImportRowResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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

    private final ImportService imports;
    private final ImportWorker worker;

    public ImportsController(ImportService imports, ImportWorker worker) {
        this.imports = imports;
        this.worker = worker;
    }

    /**
     * Carica un file (multipart, parte {@code file}) e lo mette in coda: {@code 202} con il lavoro in {@code QUEUED}.
     * Errori dell'intero file → {@code 422} ({@code IMPORT_EMPTY, IMPORT_FILE_TOO_LARGE, IMPORT_FORMAT_UNSUPPORTED,
     * IMPORT_FORMAT_MISMATCH, IMPORT_NOT_TEXT, IMPORT_INVALID, IMPORT_TOO_MANY_ROWS, IMPORT_KIND_NOT_SUPPORTED}).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresRole({Role.ADMIN, Role.CARE})
    public ResponseEntity<ImportJob> create(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "kind", required = false) String kind,
            @RequestPart(value = "source", required = false) String source,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        if (file == null) {
            throw LhException.validation("IMPORT_EMPTY", "Allega un file (parte «file» del modulo).",
                    java.util.List.of(new LhException.FieldError("file", "obbligatorio")));
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
        return imports.get(id);
    }

    /** Rapporto per riga (solo le righe non accettate), in ordine di riga; filtro {@code outcome}. */
    @GetMapping("/{id}/rows")
    public PageResponse<ImportRowResult> rows(@PathVariable String id,
                                              @RequestParam(required = false) String outcome,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return imports.rows(id, outcome, page, size);
    }

    /** Rapporto esiti scaricabile in CSV (celle neutralizzate come formule, nessun dato personale). */
    @GetMapping("/{id}/report.csv")
    public ResponseEntity<String> report(@PathVariable String id) {
        String csv = imports.reportCsv(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-" + id + "-esiti.csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    /**
     * «Riprova non abbinati» (BO-32): rivaluta le righe del monitor di questo import ancora {@code UNMATCHED}, a blocchi
     * dopo la riga {@code afterRow} (default 0); si continua con {@code nextAfterRow} finché non è nullo.
     */
    @PostMapping("/{id}/retry-unmatched")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public ImportService.RetryResult retryUnmatched(@PathVariable String id,
                                                    @RequestParam(defaultValue = "0") int afterRow) {
        return imports.retryUnmatched(id, afterRow);
    }
}
