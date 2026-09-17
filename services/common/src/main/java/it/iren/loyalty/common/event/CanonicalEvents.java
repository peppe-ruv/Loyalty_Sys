package it.iren.loyalty.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.PojoCloudEventData;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import io.cloudevents.jackson.PojoCloudEventDataMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Costruzione e serializzazione dell'evento canonico in formato CloudEvents 1.0 JSON. */
public final class CanonicalEvents {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private CanonicalEvents() {}

    /** Estensione CloudEvents per correlare gli eventi di uno stesso ciclo (evento → decisione → azione → nuovo evento). */
    public static final String CORRELATION_EXT = "correlationid";
    private static final ThreadLocal<String> CURRENT_CORRELATION = new ThreadLocal<>();

    /** Imposta la correlazione per gli eventi costruiti nel thread corrente (i consumer la ereditano dall'evento ricevuto). */
    public static void withCorrelation(String correlationId, Runnable r) {
        String prev = CURRENT_CORRELATION.get();
        CURRENT_CORRELATION.set(correlationId);
        try { r.run(); } finally { if (prev == null) CURRENT_CORRELATION.remove(); else CURRENT_CORRELATION.set(prev); }
    }

    public static String correlationId(CloudEvent event) {
        Object v = event.getExtension(CORRELATION_EXT);
        return v == null ? event.getId() : v.toString();
    }

    public static CloudEvent action(String source, String memberId, RewardingAction action) {
        Objects.requireNonNull(action.idempotencyKey(), "idempotencyKey");
        return CloudEventBuilder.v1()
                .withId(action.idempotencyKey())
                .withType(EventTypes.ACTION_V1)
                .withSource(URI.create(source))
                .withSubject("member:" + memberId)
                .withTime(OffsetDateTime.now())
                .withExtension(CORRELATION_EXT, correlationOrNew(action.idempotencyKey()))
                .withData("application/json", PojoCloudEventData.wrap(action, MAPPER::writeValueAsBytes))
                .build();
    }

    public static CloudEvent of(String type, String source, String subject, Object payload) {
        String id = UUID.randomUUID().toString();
        return CloudEventBuilder.v1()
                .withId(id)
                .withType(type)
                .withSource(URI.create(source))
                .withSubject(subject)
                .withTime(OffsetDateTime.now())
                .withExtension(CORRELATION_EXT, correlationOrNew(id))
                .withData("application/json", PojoCloudEventData.wrap(payload, MAPPER::writeValueAsBytes))
                .build();
    }

    private static String correlationOrNew(String fallback) {
        String c = CURRENT_CORRELATION.get();
        return c == null ? fallback : c;
    }

    public static byte[] serialize(CloudEvent event) {
        return EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE).serialize(event);
    }

    public static CloudEvent deserialize(byte[] bytes) {
        return EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE).deserialize(bytes);
    }

    public static String json(CloudEvent event) {
        return new String(serialize(event), StandardCharsets.UTF_8);
    }

    public static <T> T data(CloudEvent event, Class<T> type) {
        var mapped = PojoCloudEventDataMapper.from(MAPPER, type).map(Objects.requireNonNull(event.getData()));
        return mapped.getValue();
    }

    public static String memberId(CloudEvent event) {
        String s = event.getSubject();
        return s != null && s.startsWith("member:") ? s.substring("member:".length()) : s;
    }
}
