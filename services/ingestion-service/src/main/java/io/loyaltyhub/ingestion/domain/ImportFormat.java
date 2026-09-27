package io.loyaltyhub.ingestion.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Formati di un import file (F2-ING-02, docs/18 §3.6 «NDJSON/CSV»): {@code CSV} con intestazione, {@code NDJSON} (un
 * CloudEvent per riga) e {@code JSON} (un array di CloudEvent, la stessa forma del corpo di {@code POST /v1/events/batch}).
 * Il formato si deduce dall'estensione del nome del file; il contenuto deve poi corrispondere ({@link ImportParser}).
 */
public enum ImportFormat {
    CSV(Set.of("csv"), Set.of("text/csv", "application/csv", "text/plain", "application/vnd.ms-excel")),
    NDJSON(Set.of("ndjson", "jsonl"), Set.of("application/x-ndjson", "application/ndjson", "application/jsonl",
            "application/json", "text/plain")),
    JSON(Set.of("json"), Set.of("application/json", "text/json", "text/plain"));

    /** Tipi dichiarati dal browser senza informazione utile: ammessi, conta il riconoscimento del contenuto. */
    private static final Set<String> GENERIC_TYPES = Set.of("", "application/octet-stream");

    private final Set<String> extensions;
    private final Set<String> contentTypes;

    ImportFormat(Set<String> extensions, Set<String> contentTypes) {
        this.extensions = extensions;
        this.contentTypes = contentTypes;
    }

    /** Formato dall'estensione del nome del file (senza distinzione di maiuscole); vuoto se non ammessa. */
    public static Optional<ImportFormat> fromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (ImportFormat f : values()) {
            if (f.extensions.contains(ext)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /** Il tipo di contenuto dichiarato nella parte multipart è compatibile con il formato (parametri ignorati)? */
    public boolean acceptsContentType(String contentType) {
        String base = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return GENERIC_TYPES.contains(base) || contentTypes.contains(base);
    }
}
