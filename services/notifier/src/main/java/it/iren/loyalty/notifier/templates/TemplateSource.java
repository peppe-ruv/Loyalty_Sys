package it.iren.loyalty.notifier.templates;

import java.util.Optional;

/** Porta verso il backoffice: modello attivo per evento, canale e locale (fallback sul locale di default). */
public interface TemplateSource {
    Optional<MessageTemplate> find(String eventType, MessageTemplate.Channel channel, String locale);
}
