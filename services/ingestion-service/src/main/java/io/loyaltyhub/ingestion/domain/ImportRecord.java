package io.loyaltyhub.ingestion.domain;

import tools.jackson.databind.JsonNode;

/**
 * Un record di un import file (F2-ING-02) già letto dal formato: gli attributi del CloudEvent in ingresso (docs/05 §2) o,
 * se il record non si legge come evento, {@code error} (la riga diventa {@code INVALID} nel rapporto).
 *
 * @param row numero del record di dati, da 1 (intestazione CSV e righe vuote escluse)
 */
public record ImportRecord(int row, String specversion, String id, String source, String type, String subject,
                           String time, JsonNode data, String error) {

    public static ImportRecord invalid(int row, String id, String error) {
        return new ImportRecord(row, null, id, null, null, null, null, null, error);
    }

    public boolean readable() {
        return error == null;
    }
}
