package io.loyaltyhub.notifier.templates;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modello di messaggio per evento e canale (RF-77): il backoffice
 * lo redige per ogni tipo di evento (movimento, cambio tier, riscatto, vincita, scadenza punti, adesione, referral) e
 * canale (EMAIL, SMS, PUSH, IN_APP), con segnaposto {@code {{campo}}} risolti sul payload dell'evento. Locale per la
 * multilingua (it di default, en per i clienti stranieri).
 */
public record MessageTemplate(String eventType, Channel channel, String locale, String subject, String body, boolean active) {
    public enum Channel { EMAIL, SMS, PUSH, IN_APP }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    public String render(String text, Map<String, Object> data) {
        if (text == null) return "";
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup(data, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Rendered render(Map<String, Object> data) { return new Rendered(channel, render(subject, data), render(body, data)); }
    public record Rendered(Channel channel, String subject, String body) {}

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> data, String path) {
        Object cur = data;
        for (String p : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = ((Map<String, Object>) map).get(p);
        }
        return cur;
    }
}
