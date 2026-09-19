package io.loyaltyhub.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** {@link ObjectMapper} canonico del sistema: date RFC 3339 (ISO-8601), niente timestamp numerici. */
public final class LhJson {

    private static final ObjectMapper MAPPER = create();

    private LhJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Costruisce un mapper con la configurazione condivisa (usato anche dal bean Spring). */
    public static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        // Carica i moduli sul classpath: JavaTime (date RFC 3339), ParameterNames (record), Jdk8.
        mapper.findAndRegisterModules();
        return mapper
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
