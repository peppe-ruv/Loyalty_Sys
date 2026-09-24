package io.loyaltyhub.insight.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Voce DLQ (docs/servizi/insight-service.md §2, BO-27): un messaggio che un consumer non è riuscito a elaborare,
 * con i dati degli header {@code lh-*} del record su {@code lh.dlq.v1} (docs/04 §5). {@code family} è la famiglia
 * del {@code type} d'origine: solo le {@code ACTION} si possono riprocessare (§5).
 */
public record DlqEntry(
        String id,
        String eventId,
        String originalTopic,
        String originalType,
        String family,
        String consumer,
        String errorCode,
        String errorClass,
        String errorMessage,
        String errorStack,
        Boolean retryable,
        Integer attempts,
        String memberId,
        String correlationId,
        JsonNode payload,
        Instant firstSeenAt,
        String status,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote) {

    public static final String OPEN = "OPEN";
    public static final String REPROCESSED = "REPROCESSED";
    public static final String DISCARDED = "DISCARDED";

    /** Tipo senza prefisso di famiglia (es. {@code app.login.daily}). */
    @JsonProperty("shortType")
    public String shortType() {
        if (originalType == null) {
            return null;
        }
        String prefix = "io.loyaltyhub." + family.toLowerCase() + ".";
        return originalType.startsWith(prefix) ? originalType.substring(prefix.length()) : originalType;
    }

    /** Riprocessabile da qui: solo un'azione ancora aperta (docs §5; effetti e fatti → {@code 409 NOT_REPROCESSABLE}). */
    @JsonProperty("reprocessable")
    public boolean reprocessable() {
        return OPEN.equals(status) && "ACTION".equals(family);
    }
}
