package it.iren.loyalty.notifier;

import it.iren.loyalty.notifier.templates.MessageTemplate;

/** Porta verso i canali di contatto aziendali (email/SMS/push): il recapito resta nel CRM (D12), qui solo l'invio. */
public interface MessageSender {
    void send(String memberId, MessageTemplate.Rendered message);
}
