package io.loyaltyhub.notifier.delivery;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Instradamento delle consegne (RF-132), configurato nel backoffice (collezione {@code delivery-routing}): per ogni
 * azione di contatto l'ordine dei canali (il canale scelto dal decision-service viene provato per primo), i canali
 * abilitati, i limiti giornalieri per canale, le ore di silenzio (per messaggi push/sms/email: se scatta, si rimanda
 * all'inbox o si accoda), e il modello di messaggio da usare per azione/riferimento.
 *
 * @param maxPerDayByChannel limite di consegne al giorno per membro e canale (0 = nessuno)
 * @param quietHoursFallback canale usato durante le ore di silenzio al posto di push/sms/email (es. app), null = rinvia
 * @param templateByAction chiave "SEND_MESSAGE" o "SEND_MESSAGE:<reference>" → id modello (eventType nel TemplateSource)
 */
public record DeliveryRouting(String id, String version, Map<String, List<String>> channelOrderByAction, List<String> enabledChannels,
                              Map<String, Integer> maxPerDayByChannel, Integer quietHoursFrom, Integer quietHoursTo, String quietHoursFallback,
                              Map<String, String> templateByAction, boolean emitActions) {
    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");
    private static final List<String> INTERRUPTIVE = List.of("push", "sms", "email");

    public static DeliveryRouting example() {
        return new DeliveryRouting("default", "1",
                Map.of("SEND_MESSAGE", List.of("push", "app", "email", "sms"), "SHOW_OFFER", List.of("app", "web", "email"), "ASK_FOR_FEEDBACK", List.of("app", "email")),
                List.of("app", "web", "push", "email", "sms", "webhook", "operator"),
                Map.of("push", 2, "sms", 1, "email", 1, "app", 5), 21, 8, "app",
                Map.of("SEND_MESSAGE", "decision.message", "SHOW_OFFER", "decision.offer", "ASK_FOR_FEEDBACK", "decision.feedback"), true);
    }

    public boolean inQuietHours(Instant now) {
        if (quietHoursFrom == null || quietHoursTo == null) return false;
        int h = ZonedDateTime.ofInstant(now, ZONE).getHour();
        return quietHoursFrom <= quietHoursTo ? (h >= quietHoursFrom && h < quietHoursTo) : (h >= quietHoursFrom || h < quietHoursTo);
    }

    public static boolean interruptive(String channel) { return INTERRUPTIVE.contains(channel); }

    public boolean enabled(String channel) { return enabledChannels == null || enabledChannels.isEmpty() || enabledChannels.contains(channel); }

    public List<String> orderFor(String action) { return channelOrderByAction == null ? List.of() : channelOrderByAction.getOrDefault(action, List.of()); }

    public String templateFor(String action, String reference) {
        if (templateByAction == null) return "decision." + action.toLowerCase();
        String t = reference == null ? null : templateByAction.get(action + ":" + reference);
        return t != null ? t : templateByAction.getOrDefault(action, "decision." + action.toLowerCase());
    }
}
