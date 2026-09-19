package io.loyaltyhub.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Envelope CloudEvents 1.0 (JSON strutturato) del sistema — contratto tra i servizi (docs/05 §2).
 * I nomi dei campi coincidono con gli attributi CloudEvents; i consumatori tollerano campi sconosciuti.
 *
 * @param <T> tipo del payload {@code data} (spesso {@code JsonNode} in ingresso, un record specifico in uscita)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LhEvent<T>(
        String specversion,
        String id,
        String source,
        String type,
        String subject,
        Instant time,
        String datacontenttype,
        String dataschema,
        String lhtenant,
        String lhcorrelationid,
        String lhcausationid,
        Integer lhhop,
        String lhactor,
        T data
) {
    public static final String SPEC_VERSION = "1.0";
    public static final String DATA_CONTENT_TYPE = "application/json";
    public static final String TENANT = "aurora";

    /** {@code memberId} estratto da {@code subject} ({@code member:<id>}); {@code null} se il subject non è un membro. */
    public String memberId() {
        if (subject != null && subject.startsWith("member:")) {
            return subject.substring("member:".length());
        }
        return null;
    }

    /** Chiave di partizione Kafka: il {@code memberId} se presente, altrimenti il {@code subject} intero (docs/05 §1). */
    public String partitionKey() {
        String m = memberId();
        return m != null ? m : subject;
    }

    public LhFamily family() {
        return LhFamily.of(type);
    }

    public int hopOrZero() {
        return lhhop == null ? 0 : lhhop;
    }
}
