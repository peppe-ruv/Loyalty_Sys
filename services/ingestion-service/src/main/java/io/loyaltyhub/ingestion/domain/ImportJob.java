package io.loyaltyhub.ingestion.domain;

import java.time.Instant;

/**
 * Lavoro di import file (F2-ING-02, BO-32; tabella {@code import_job}). Ciclo di vita:
 * {@code QUEUED → RUNNING → DONE | FAILED}; un {@code RUNNING} il cui lavoratore si è fermato torna in lavorazione dal
 * punto di ripresa ({@code rowsDone}). Il contenuto del file non fa parte della vista.
 *
 * @param counts esiti delle righe già elaborate (il rapporto «accettati/duplicati/respinti/non abbinati», più le righe
 *               non leggibili come evento)
 */
public record ImportJob(
        String id,
        String kind,
        String format,
        String fileName,
        int sizeBytes,
        String sha256,
        String defaultSource,
        Status status,
        int rowsTotal,
        int rowsDone,
        OutcomeCounts counts,
        int attempts,
        String errorDetail,
        String createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {

    public enum Status {
        QUEUED, RUNNING, DONE, FAILED
    }

    public static final String KIND_EVENTS = "EVENTS";
    /** Attributi dei membri (docs/18 §3.16 punto 2): arrivano con M13.5, non in M8.7 (Q-370). */
    public static final String KIND_ATTRIBUTES = "ATTRIBUTES";
}
