package io.loyaltyhub.notifier;

import io.loyaltyhub.notifier.templates.MessageTemplate;

/** Porta verso i canali di contatto aziendali (email/SMS/push): il recapito resta nel CRM (ADR-012), qui solo l'invio. */
public interface MessageSender {
    void send(String memberId, MessageTemplate.Rendered message);
}
