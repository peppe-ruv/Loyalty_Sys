package io.loyaltyhub.engagement.application;

import io.loyaltyhub.engagement.infra.MemberSnapshotRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Contesto dei segnaposto dei template (docs/servizi/engagement-service.md §5): {@code data} = payload del fatto (o i
 * {@code params} dell'effetto {@code message.send}), {@code member} = snapshot locale ({@code memberId, firstName,
 * status, tierCode, segments}), {@code event} = metadati dell'evento sorgente ({@code id, type} in forma breve,
 * {@code time, subject, source, correlationId}).
 */
@Component
public class MessageContexts {

    /** Metadati dell'evento sorgente esposti come {@code event.*}. */
    public record EventMeta(String id, String type, Instant time, String subject, String source, String correlationId) {
    }

    private final MemberSnapshotRepository members;
    private final ObjectMapper mapper;

    public MessageContexts(MemberSnapshotRepository members, ObjectMapper mapper) {
        this.members = members;
        this.mapper = mapper;
    }

    public ObjectNode build(String memberId, JsonNode data, EventMeta event) {
        ObjectNode ctx = mapper.createObjectNode();
        ctx.set("data", data == null || data.isNull() || data.isMissingNode() ? mapper.createObjectNode() : data);
        ObjectNode member = ctx.putObject("member");
        if (memberId != null) {
            member.put("memberId", memberId);
            members.find(memberId).ifPresent(s -> {
                putIfNotNull(member, "firstName", s.firstName());
                putIfNotNull(member, "status", s.status());
                putIfNotNull(member, "tierCode", s.tierCode());
                ArrayNode segments = member.putArray("segments");
                s.segments().forEach(segments::add);
            });
        }
        ObjectNode ev = ctx.putObject("event");
        if (event != null) {
            putIfNotNull(ev, "id", event.id());
            putIfNotNull(ev, "type", event.type());
            putIfNotNull(ev, "time", event.time() == null ? null : event.time().toString());
            putIfNotNull(ev, "subject", event.subject());
            putIfNotNull(ev, "source", event.source());
            putIfNotNull(ev, "correlationId", event.correlationId());
        }
        return ctx;
    }

    /** Forma breve di un {@code type} ({@code io.loyaltyhub.fact.wallet.points.earned} → {@code wallet.points.earned}). */
    public static String shortType(String type) {
        if (type == null) {
            return null;
        }
        for (String prefix : new String[]{"io.loyaltyhub.fact.", "io.loyaltyhub.effect.", "io.loyaltyhub.action."}) {
            if (type.startsWith(prefix)) {
                return type.substring(prefix.length());
            }
        }
        return type;
    }

    private static void putIfNotNull(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
