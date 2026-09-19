package io.loyaltyhub.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ObjectMapper} canonico del sistema (Jackson 3): date RFC 3339 (ISO-8601, java.time integrato),
 * omissione dei null, tolleranza ai campi sconosciuti (docs/05, docs/06 §2).
 */
public final class LhJson {

    private static final ObjectMapper MAPPER = create();

    private LhJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Costruisce un mapper con la configurazione condivisa (usato anche dal bean Spring). */
    public static ObjectMapper create() {
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }
}
