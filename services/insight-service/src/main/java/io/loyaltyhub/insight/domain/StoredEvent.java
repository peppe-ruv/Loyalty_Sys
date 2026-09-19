package io.loyaltyhub.insight.domain;

import java.time.Instant;

/**
 * Una riga dell'event store (docs/servizi/insight-service.md §2): copia di un evento osservato su un topic,
 * con gli attributi CloudEvents estratti per il filtraggio e il payload completo (troncato dalla retention).
 */
public record StoredEvent(
        String eventId,
        String topic,
        String family,
        String type,
        String shortType,
        String source,
        String memberId,
        String correlationId,
        String causationId,
        Integer hop,
        String actor,
        String errorCode,
        Instant eventTime,
        Instant receivedAt,
        int partition,
        long offset,
        String payloadJson) {
}
