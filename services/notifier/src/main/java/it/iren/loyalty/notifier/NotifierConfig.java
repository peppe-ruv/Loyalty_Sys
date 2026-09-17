package it.iren.loyalty.notifier;

import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.notifier.templates.MessageTemplate;
import it.iren.loyalty.notifier.templates.TemplateSource;
import it.iren.loyalty.notifier.webhooks.WebhookSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Adattatori di default: modelli e webhook di esempio in memoria (in produzione dal CMS), invio su log. */
@Configuration
public class NotifierConfig {
    @Bean @ConditionalOnMissingBean
    TemplateSource seedTemplates() {
        List<MessageTemplate> seed = List.of(
                new MessageTemplate(EventTypes.TIER_CHANGED_V1, MessageTemplate.Channel.EMAIL, "it", "Sei salito al livello {{event.toTier}}",
                        "Complimenti! Da oggi sei {{event.toTier}}: scopri i premi della tua fascia.", true),
                new MessageTemplate(EventTypes.REDEMPTION_V1, MessageTemplate.Channel.PUSH, "it", "Premio confermato",
                        "Il tuo premio {{event.rewardName}} è confermato. Codice: {{event.code}}", true));
        return (type, ch, locale) -> seed.stream().filter(t -> t.active() && t.eventType().equals(type) && t.channel() == ch && t.locale().equals(locale)).findFirst()
                .or(() -> seed.stream().filter(t -> t.active() && t.eventType().equals(type) && t.channel() == ch && t.locale().equals("it")).findFirst());
    }

    @Bean @ConditionalOnMissingBean
    WebhookSource seedWebhooks() {
        String url = System.getenv("WEBHOOK_DEMO_URL");
        return () -> url == null ? List.of() : List.of(new it.iren.loyalty.notifier.webhooks.WebhookSubscription("demo", url, System.getenv().getOrDefault("WEBHOOK_DEMO_SECRET", "dev-only"),
                Set.of(EventTypes.TIER_CHANGED_V1, EventTypes.REDEMPTION_V1), Map.of(), true, 5));
    }

    @Bean @ConditionalOnMissingBean
    MessageSender logSender() {
        var log = LoggerFactory.getLogger("notifier.messages");
        return (memberId, m) -> log.info("[{}] to {}: {} — {}", m.channel(), memberId, m.subject(), m.body());
    }
}
