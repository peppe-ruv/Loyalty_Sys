package io.loyaltyhub.notifier;

import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.notifier.templates.MessageTemplate;
import io.loyaltyhub.notifier.templates.TemplateSource;
import io.loyaltyhub.notifier.webhooks.WebhookSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Adattatori di default: modelli e webhook di esempio in memoria (in produzione dal CMS), invio su log. */
@Configuration
public class NotifierConfig {

    /** Forma della risposta del CMS: un oggetto JSON con `docs`, `totalDocs`, … */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };
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
        return () -> url == null ? List.of() : List.of(new io.loyaltyhub.notifier.webhooks.WebhookSubscription("demo", url, System.getenv().getOrDefault("WEBHOOK_DEMO_SECRET", "dev-only"),
                Set.of(EventTypes.TIER_CHANGED_V1, EventTypes.REDEMPTION_V1), Map.of(), true, 5));
    }

    /** Routing delle consegne (RF-132) dal backoffice (collezione {@code delivery-routing}), con cache e fallback al seed. */
    @Bean @ConditionalOnMissingBean
    @SuppressWarnings("unchecked")
    java.util.function.Supplier<io.loyaltyhub.notifier.delivery.DeliveryRouting> deliveryRouting(org.springframework.web.client.RestClient.Builder builder) {
        var cms = builder.baseUrl(System.getenv().getOrDefault("CMS_URL", "http://cms:3000")).build();
        long ttl = Long.parseLong(System.getenv().getOrDefault("CMS_CACHE_MS", "30000"));
        var log = LoggerFactory.getLogger(NotifierConfig.class);
        return new java.util.function.Supplier<>() {
            private volatile io.loyaltyhub.notifier.delivery.DeliveryRouting last = io.loyaltyhub.notifier.delivery.DeliveryRouting.example();
            private volatile long at = 0;
            @Override public io.loyaltyhub.notifier.delivery.DeliveryRouting get() {
                long now = System.currentTimeMillis();
                if (now - at < ttl) return last;
                try {
                    Map<String, Object> body = cms.get().uri("/api/delivery-routing?where[status][equals]=published&limit=1&depth=0").retrieve().body(JSON_OBJECT);
                    // `docs` è una lista di documenti JSON: il contenuto non è tipizzabile a compile time.
                    List<Map<String, Object>> docs = body == null ? List.of() : (List<Map<String, Object>>) body.getOrDefault("docs", List.of());
                    if (!docs.isEmpty()) last = toRouting(docs.get(0));
                } catch (Exception e) { log.warn("cms delivery-routing unavailable ({}), keeping {}", e.toString(), last.id()); }
                at = now;
                return last;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static io.loyaltyhub.notifier.delivery.DeliveryRouting toRouting(Map<String, Object> d) {
        var seed = io.loyaltyhub.notifier.delivery.DeliveryRouting.example();
        Map<String, List<String>> order = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) d.getOrDefault("routes", List.of()))
            order.put(String.valueOf(r.get("action")), ((List<Map<String, Object>>) r.getOrDefault("channels", List.of())).stream().map(c -> String.valueOf(c.get("channel"))).toList());
        Map<String, Integer> caps = new java.util.LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) d.getOrDefault("maxPerDayByChannel", List.of())) caps.put(String.valueOf(c.get("channel")), ((Number) c.getOrDefault("max", 0)).intValue());
        Map<String, String> templates = new java.util.LinkedHashMap<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) d.getOrDefault("templates", List.of())) templates.put(String.valueOf(t.get("action")) + (t.get("reference") == null || String.valueOf(t.get("reference")).isBlank() ? "" : ":" + t.get("reference")), String.valueOf(t.get("templateId")));
        List<String> enabled = ((List<Object>) d.getOrDefault("enabledChannels", List.of())).stream().map(String::valueOf).toList();
        Object qf = d.get("quietHoursFrom"), qt = d.get("quietHoursTo");
        return new io.loyaltyhub.notifier.delivery.DeliveryRouting(String.valueOf(d.getOrDefault("code", d.get("id"))), String.valueOf(d.getOrDefault("version", 1)),
                order.isEmpty() ? seed.channelOrderByAction() : order, enabled.isEmpty() ? seed.enabledChannels() : enabled, caps.isEmpty() ? seed.maxPerDayByChannel() : caps,
                qf instanceof Number n ? n.intValue() : null, qt instanceof Number n2 ? n2.intValue() : null, d.get("quietHoursFallback") == null ? null : d.get("quietHoursFallback").toString(),
                templates.isEmpty() ? seed.templateByAction() : templates, !(d.get("emitActions") instanceof Boolean b) || b);
    }

    /**
     * Adattatori di canale: inbox app/web, fornitori email/sms/push, webhook CRM, coda operatore.
     *
     * <p>Per email, SMS e push si usa il fornitore configurato ({@code EMAIL_PROVIDER_URL}, {@code SMS_PROVIDER_URL},
     * {@code PUSH_PROVIDER_URL} con la rispettiva chiave); dove non è configurato resta l'invio su log, che serve in
     * locale e nei collaudi. Nessun recapito passa da qui: il fornitore risolve il destinatario dall'id membro
     * (ADR-012), la piattaforma non conserva email né numeri in chiaro.
     */
    @Bean List<io.loyaltyhub.notifier.delivery.ChannelAdapter> channelAdapters(org.springframework.jdbc.core.JdbcTemplate jdbc, MessageSender sender, org.springframework.web.client.RestClient.Builder builder) {
        String crm = System.getenv("CRM_DELIVERY_URL");
        return List.of(
                io.loyaltyhub.notifier.delivery.Adapters.appInbox(jdbc, "app"),
                io.loyaltyhub.notifier.delivery.Adapters.appInbox(jdbc, "web"),
                canale(builder, sender, "email", "EMAIL_PROVIDER_URL", "EMAIL_PROVIDER_KEY", MessageTemplate.Channel.EMAIL),
                canale(builder, sender, "sms", "SMS_PROVIDER_URL", "SMS_PROVIDER_KEY", MessageTemplate.Channel.SMS),
                canale(builder, sender, "push", "PUSH_PROVIDER_URL", "PUSH_PROVIDER_KEY", MessageTemplate.Channel.PUSH),
                io.loyaltyhub.notifier.delivery.Adapters.webhook(crm == null ? null : builder.baseUrl(crm).build(), System.getenv().getOrDefault("CRM_DELIVERY_SECRET", "dev-only")),
                io.loyaltyhub.notifier.delivery.Adapters.operatorQueue(jdbc));
    }

    /** Fornitore reale se l'URL c'è, invio su log altrimenti: lo stesso servizio funziona in locale e in esercizio. */
    private static io.loyaltyhub.notifier.delivery.ChannelAdapter canale(org.springframework.web.client.RestClient.Builder builder, MessageSender sender,
                                                                        String channel, String urlEnv, String keyEnv, MessageTemplate.Channel templateChannel) {
        String url = System.getenv(urlEnv);
        if (url == null || url.isBlank()) return io.loyaltyhub.notifier.delivery.Adapters.sender(sender, channel, templateChannel);
        var client = builder.baseUrl(url).build();
        return io.loyaltyhub.notifier.delivery.Adapters.provider(client, channel, System.getenv(keyEnv));
    }

    @Bean @ConditionalOnMissingBean
    MessageSender logSender() {
        var log = LoggerFactory.getLogger("notifier.messages");
        return (memberId, m) -> log.info("[{}] to {}: {} — {}", m.channel(), memberId, m.subject(), m.body());
    }
}
