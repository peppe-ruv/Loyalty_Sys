package io.loyaltyhub.notifier.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.loyaltyhub.notifier.MessageSender;
import io.loyaltyhub.notifier.templates.MessageTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.util.Map;

/**
 * Adattatori di canale forniti (RF-132). APP: inbox nel database (letta dal BFF/app); EMAIL, SMS, PUSH: fornitori
 * aziendali tramite {@link MessageSender}; WEBHOOK: sistema esterno (CRM, marketing automation) con firma HMAC del
 * WebhookDispatcher; OPERATOR: coda per il call center; WEB: alias dell'inbox (il sito la legge come "offerte per te").
 */
public final class Adapters {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Adapters() {}

    public static ChannelAdapter appInbox(JdbcTemplate jdbc, String channel) {
        return new ChannelAdapter() {
            @Override public String channel() { return channel; }
            @Override public Result deliver(Delivery d) {
                try {
                    jdbc.update("INSERT INTO notifier.inbox_message(id, member_id, decision_id, action, reference, subject, body, params, expires_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
                            d.deliveryId(), d.memberId(), d.decisionId(), d.action(), d.reference(), d.subject(), d.body(), MAPPER.writeValueAsString(d.params() == null ? Map.of() : d.params()),
                            d.expiresAt() == null ? null : Timestamp.from(d.expiresAt()));
                    return Result.presented(d.deliveryId());
                } catch (Exception e) { return Result.failed(e.toString()); }
            }
        };
    }

    public static ChannelAdapter sender(MessageSender sender, String channel, MessageTemplate.Channel templateChannel) {
        return new ChannelAdapter() {
            @Override public String channel() { return channel; }
            @Override public Result deliver(Delivery d) {
                sender.send(d.memberId(), new MessageTemplate.Rendered(templateChannel, d.subject(), d.body()));
                return Result.sent(null);
            }
        };
    }

    /** Sistema esterno (CRM): riceve la consegna e la esegue con i propri mezzi; l'URL viene dal routing o dall'ambiente. */
    public static ChannelAdapter webhook(RestClient client, String secret) {
        return new ChannelAdapter() {
            @Override public String channel() { return "webhook"; }
            @Override public Result deliver(Delivery d) {
                if (client == null) return Result.failed("CRM_DELIVERY_URL not configured");
                Map<String, Object> body = Map.of("deliveryId", d.deliveryId(), "memberId", d.memberId(), "decisionId", d.decisionId() == null ? "" : d.decisionId(), "action", d.action(),
                        "reference", d.reference() == null ? "" : d.reference(), "subject", d.subject() == null ? "" : d.subject(), "body", d.body() == null ? "" : d.body(), "params", d.params() == null ? Map.of() : d.params());
                try {
                    String json = MAPPER.writeValueAsString(body);
                    var res = client.post().uri("").header("X-Loyalty-Signature", new io.loyaltyhub.notifier.webhooks.WebhookSubscription("delivery", "", secret, java.util.Set.of(), Map.of(), true, 0).sign(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))).header("Content-Type", "application/json").body(json).retrieve().toEntity(String.class);
                    return res.getStatusCode().is2xxSuccessful() ? Result.sent(res.getBody()) : Result.failed("HTTP " + res.getStatusCode());
                } catch (Exception e) { return Result.failed(e.toString()); }
            }
        };
    }

    /**
     * Fornitore reale di un canale di contatto (push, email, SMS): {@code POST url} con il messaggio già reso e
     * l'identificativo del membro. <b>Nessun recapito viaggia da qui</b>: indirizzo e numero restano nel CRM
     * (ADR-012), il fornitore li risolve dal {@code memberId} o dal proprio registro dispositivi. La risposta può
     * portare un identificativo di tracciamento ({@code id}, {@code messageId} o {@code providerRef}), che finisce nel
     * log consegne e permette di riconciliare con il fornitore.
     *
     * <p>Un errore non è un'eccezione che si propaga: è un {@code FAILED} con il motivo, così la consegna prova il
     * canale successivo del routing e l'operatore ritrova la riga nella coda del reinvio.
     */
    public static ChannelAdapter provider(RestClient client, String channel, String apiKey) {
        return new ChannelAdapter() {
            @Override public String channel() { return channel; }
            @Override public Result deliver(Delivery d) {
                if (client == null) return Result.failed("fornitore non configurato per il canale " + channel);
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("deliveryId", d.deliveryId());
                body.put("memberId", d.memberId());
                body.put("channel", channel);
                body.put("subject", d.subject() == null ? "" : d.subject());
                body.put("body", d.body() == null ? "" : d.body());
                body.put("params", d.params() == null ? Map.of() : d.params());
                try {
                    var req = client.post().uri("").header("Content-Type", "application/json");
                    if (apiKey != null && !apiKey.isBlank()) req = req.header("Authorization", "Bearer " + apiKey);
                    var res = req.body(MAPPER.writeValueAsString(body)).retrieve().toEntity(String.class);
                    if (!res.getStatusCode().is2xxSuccessful()) return Result.failed("HTTP " + res.getStatusCode());
                    return Result.sent(providerRef(res.getBody(), d.deliveryId()));
                } catch (Exception e) { return Result.failed(e.toString()); }
            }
        };
    }

    /** Identificativo del fornitore, se la risposta ne porta uno; altrimenti resta quello della consegna. */
    static String providerRef(String body, String fallback) {
        if (body == null || body.isBlank()) return fallback;
        try {
            var node = MAPPER.readTree(body);
            for (String campo : new String[] {"providerRef", "messageId", "id"}) {
                var v = node.get(campo);
                if (v != null && !v.isNull()) return v.asText();
            }
        } catch (Exception ignored) { /* risposta non JSON: va bene lo stesso, il fornitore ha accettato */ }
        return fallback;
    }

    public static ChannelAdapter operatorQueue(JdbcTemplate jdbc) {
        return new ChannelAdapter() {
            @Override public String channel() { return "operator"; }
            @Override public Result deliver(Delivery d) {
                jdbc.update("INSERT INTO notifier.operator_queue(id, member_id, decision_id, action, reference, note) VALUES (?,?,?,?,?,?)",
                        d.deliveryId(), d.memberId(), d.decisionId(), d.action(), d.reference(), (d.subject() == null ? "" : d.subject() + " — ") + (d.body() == null ? "" : d.body()));
                return Result.queued(d.deliveryId());
            }
        };
    }
}
