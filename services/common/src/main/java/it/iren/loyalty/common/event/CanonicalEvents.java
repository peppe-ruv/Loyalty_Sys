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

    public static CloudEvent action(String source, String memberId, RewardingAction action) {
        Objects.requireNonNull(action.idempotencyKey(), "idempotencyKey");
        return CloudEventBuilder.v1()
                .withId(action.idempotencyKey())
                .withType(EventTypes.ACTION_V1)
                .withSource(URI.create(source))
                .withSubject("member:" + memberId)
                .withTime(OffsetDateTime.now())
                .withData("application/json", PojoCloudEventData.wrap(action, MAPPER::writeValueAsBytes))
                .build();
    }

    public static CloudEvent of(String type, String source, String subject, Object payload) {
        return CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withType(type)
                .withSource(URI.create(source))
                .withSubject(subject)
                .withTime(OffsetDateTime.now())
                .withData("application/json", PojoCloudEventData.wrap(payload, MAPPER::writeValueAsBytes))
                .build();
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
