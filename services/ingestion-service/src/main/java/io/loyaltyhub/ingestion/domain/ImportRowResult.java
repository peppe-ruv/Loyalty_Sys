package io.loyaltyhub.ingestion.domain;

/**
 * Esito di una riga non accettata di un import (tabella {@code import_row}, rapporto di BO-32).
 *
 * @param lineNumber     linea fisica del file dove comincia il record (per ritrovarlo); assente nello storico demo
 * @param detail         motivo; per {@code UNMATCHED} è quello della riga del monitor (con il soggetto, finché
 *                       l'anonimizzazione non lo toglie), mai copiato nel rapporto
 * @param inboundEventId riga del monitor ingressi (BO-26) scritta dalla pipeline; {@code null} per {@code INVALID}
 * @param currentStatus  esito attuale di quella riga nel monitor (una riga {@code UNMATCHED} può essere stata riprovata
 *                       o abbinata dopo l'import); {@code null} se la riga non esiste più (pulizia a 7 giorni)
 */
public record ImportRowResult(
        int rowNumber,
        Integer lineNumber,
        String eventId,
        ItemOutcome outcome,
        String rejectCode,
        String detail,
        String inboundEventId,
        String currentStatus
) {
}
