package it.iren.loyalty.notifier.webhooks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Sottoscrizione webhook (RF-78), equivalente della "marketing automation" di Open Loyalty: un sistema esterno (CRM,
 * marketing automation, partner) riceve in POST gli eventi dei tipi sottoscritti, firmati HMAC-SHA256 con il segreto
 * condiviso ({@code X-Loyalty-Signature}) e con intestazioni statiche opzionali (es. chiave API del destinatario).
 * I topic Kafka (RI-06) restano il canale principale per i sistemi interni; i webhook servono chi non è sul bus.
 */
public record WebhookSubscription(String id, String url, String secret, Set<String> eventTypes, Map<String, String> headers, boolean active, int maxRetries) {
    public boolean wants(String eventType) { return active && (eventTypes == null || eventTypes.isEmpty() || eventTypes.contains(eventType)); }

    public String sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
