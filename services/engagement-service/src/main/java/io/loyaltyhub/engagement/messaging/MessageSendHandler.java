package io.loyaltyhub.engagement.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.engagement.application.InboxService;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * Effetto {@code message.send} (EVT-EFF-05, effetto {@code SEND_MESSAGE} di docs/03 §3.4): consegna il template nell'inbox.
 * Lo spazio {@code data.*} del template sono i campi comuni dell'effetto più i {@code params} (che vincono in caso di
 * omonimia). Deduplica sull'{@code effectId} (stabile per azione, campagna e indice: docs/03 §3.5). Template sconosciuto
 * o membro assente → DLQ non ritentabile.
 */
@Component
public class MessageSendHandler implements EventHandler {

    private final TemplateRepository templates;
    private final InboxService inbox;

    public MessageSendHandler(TemplateRepository templates, InboxService inbox) {
        this.templates = templates;
        this.inbox = inbox;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Effect.MESSAGE_SEND);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        JsonNode d = event.data();
        String memberId = event.memberId();
        if (d == null || !d.isObject() || memberId == null) {
            throw new NonRetryableEventException("INVALID_EFFECT", "message.send senza membro o dati: " + event.id());
        }
        String code = d.path("templateCode").asString("");
        MessageTemplate template = templates.find(code)
                .orElseThrow(() -> new NonRetryableEventException("TEMPLATE_NOT_FOUND", "Template sconosciuto: " + code));
        ObjectNode data = (ObjectNode) d.deepCopy();
        data.remove("params");
        JsonNode params = d.path("params");
        if (params.isObject()) {
            for (Map.Entry<String, JsonNode> e : params.properties()) {
                data.set(e.getKey(), e.getValue());
            }
        }
        // SPEC-GAP: Q-74 — per l'effetto la chiave di deduplica è l'effectId (stabile anche se l'effetto venisse
        // riemesso con un altro id d'evento) e data.* = campi dell'effetto + params.
        String sourceId = d.hasNonNull("effectId") && !d.get("effectId").asString().isBlank() ? d.get("effectId").asString() : event.id();
        inbox.deliver(memberId, template, data, event, sourceId);
    }
}
