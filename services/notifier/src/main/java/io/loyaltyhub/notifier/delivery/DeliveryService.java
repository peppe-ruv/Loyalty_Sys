package io.loyaltyhub.notifier.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.metrics.LoyaltyMetrics;
import io.loyaltyhub.notifier.templates.MessageTemplate;
import io.loyaltyhub.notifier.templates.TemplateSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Delivery (RF-132): consuma DECISION_V1 e consegna le azioni di contatto (SEND_MESSAGE, SHOW_OFFER, ASK_FOR_FEEDBACK)
 * sul canale deciso, con fallback secondo il routing del backoffice, limiti giornalieri e ore di silenzio.
 * Ogni consegna produce DELIVERY_V1 (per Customer 360, BI e pressione commerciale) e, se abilitato, l'azione canonica
 * OFFER_PRESENTED / MESSAGE_SENT / FEEDBACK_REQUESTED, così le campagne possono reagire (RF-133). Idempotente per
 * decisione+azione+riferimento.
 */
@Component
public class DeliveryService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE = "urn:loyaltyhub:delivery";
    private static final Set<String> CONTACT_ACTIONS = Set.of("SEND_MESSAGE", "SHOW_OFFER", "ASK_FOR_FEEDBACK");

    private final Map<String, ChannelAdapter> adapters = new HashMap<>();
    private final Supplier<DeliveryRouting> routing;
    private final TemplateSource templates;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final LoyaltyMetrics metrics;

    public DeliveryService(List<ChannelAdapter> adapters, Supplier<DeliveryRouting> routing, TemplateSource templates, JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, LoyaltyMetrics metrics) {
        adapters.forEach(a -> this.adapters.put(a.channel(), a));
        this.routing = routing; this.templates = templates; this.jdbc = jdbc; this.kafka = kafka; this.metrics = metrics;
    }

    @KafkaListener(topics = EventTypes.TOPIC_DECISIONS, groupId = "notifier-delivery")
    @SuppressWarnings("unchecked")
    public void onDecision(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        if (!EventTypes.DECISION_V1.equals(e.getType())) return;
        Map<String, Object> d = CanonicalEvents.data(e, Map.class);
        String memberId = CanonicalEvents.memberId(e), decisionId = String.valueOf(d.get("decisionId")), correlation = CanonicalEvents.correlationId(e);
        Instant expires = d.get("expiresAt") == null ? null : Instant.parse(d.get("expiresAt").toString());
        for (Map<String, Object> a : (List<Map<String, Object>>) d.getOrDefault("actions", List.of())) {
            String action = String.valueOf(a.get("action"));
            if (!CONTACT_ACTIONS.contains(action)) continue;
            Map<String, Object> params = a.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            deliver(memberId, decisionId, action, str(a.get("reference")), str(a.get("channel")), params, expires, correlation, Instant.now());
        }
    }

    /** Consegna con fallback; ritorna l'esito finale. Usabile anche a richiesta (operatore, campagne). */
    public ChannelAdapter.Result deliver(String memberId, String decisionId, String action, String reference, String preferredChannel, Map<String, Object> params, Instant expiresAt, String correlationId, Instant now) {
        return deliver(memberId, decisionId, action, reference, preferredChannel, params, expiresAt, correlationId, now, false);
    }

    /**
     * @param forzata reinvio deciso da un operatore: salta il controllo di già consegnato (è proprio ciò che si vuole
     *                rifare) e i limiti giornalieri di canale, che valgono per le consegne automatiche
     */
    public ChannelAdapter.Result deliver(String memberId, String decisionId, String action, String reference, String preferredChannel,
                                         Map<String, Object> params, Instant expiresAt, String correlationId, Instant now, boolean forzata) {
        if (!forzata && decisionId != null && alreadyDelivered(decisionId, action, reference)) return new ChannelAdapter.Result("SKIPPED", "already delivered", null);
        DeliveryRouting r = routing.get();
        List<String> order = new ArrayList<>();
        if (preferredChannel != null) order.add(preferredChannel);
        for (String c : r.orderFor(action)) if (!order.contains(c)) order.add(c);
        boolean quiet = r.inQuietHours(now);
        ChannelAdapter.Result last = ChannelAdapter.Result.failed("no channel");
        for (String channel : order) {
            String ch = channel;
            if (quiet && DeliveryRouting.interruptive(ch)) {
                if (r.quietHoursFallback() == null) { last = new ChannelAdapter.Result("SKIPPED", "quiet hours", null); continue; }
                ch = r.quietHoursFallback(); // es. push → inbox in app durante le ore di silenzio
            }
            if (!r.enabled(ch)) { last = new ChannelAdapter.Result("SKIPPED", "channel disabled: " + ch, null); continue; }
            ChannelAdapter adapter = adapters.get(ch);
            if (adapter == null) { last = new ChannelAdapter.Result("SKIPPED", "no adapter: " + ch, null); continue; }
            int cap = r.maxPerDayByChannel() == null ? 0 : r.maxPerDayByChannel().getOrDefault(ch, 0);
            if (!forzata && cap > 0 && deliveriesToday(memberId, ch) >= cap) { last = new ChannelAdapter.Result("SKIPPED", "daily cap " + ch, null); continue; }
            var rendered = render(r, action, reference, memberId, ch, params);
            String deliveryId = UUID.randomUUID().toString();
            var delivery = new ChannelAdapter.Delivery(deliveryId, memberId, decisionId, action, reference, ch, rendered.subject(), rendered.body(), params, expiresAt, correlationId);
            ChannelAdapter.Result res;
            try { res = adapter.deliver(delivery); } catch (Exception ex) { res = ChannelAdapter.Result.failed(ex.toString()); }
            record(delivery, res);
            metrics.delivery(ch, res.status());
            if (res.ok()) {
                publish(delivery, res);
                if (r.emitActions()) emitAction(delivery, res, correlationId);
                return res;
            }
            last = res;
        }
        if (decisionId != null) record(new ChannelAdapter.Delivery(UUID.randomUUID().toString(), memberId, decisionId, action, reference, order.isEmpty() ? "-" : order.get(0), null, null, params, expiresAt, correlationId), last);
        log.info("delivery of {} for member {} not performed: {} {}", action, memberId, last.status(), last.detail());
        return last;
    }

    private MessageTemplate.Rendered render(DeliveryRouting r, String action, String reference, String memberId, String channel, Map<String, Object> params) {
        String templateId = params.get("templateId") != null ? params.get("templateId").toString() : r.templateFor(action, reference);
        Map<String, Object> data = new HashMap<>(params);
        data.put("memberId", memberId);
        data.put("reference", reference);
        data.put("action", action);
        return render(templates, templateId, channel, action, reference, params, data);
    }

    /**
     * Canale del modello corrispondente al canale di consegna: {@code app}, {@code web}, {@code webhook} e
     * {@code operator} ricevono il testo in-app.
     */
    static MessageTemplate.Channel templateChannel(String channel) {
        return switch (channel == null ? "" : channel) {
            case "email" -> MessageTemplate.Channel.EMAIL;
            case "sms" -> MessageTemplate.Channel.SMS;
            case "push" -> MessageTemplate.Channel.PUSH;
            default -> MessageTemplate.Channel.IN_APP;
        };
    }

    /**
     * Testo da consegnare sul canale scelto: si usa il modello di <em>quel</em> canale e, se non esiste, il testo
     * generico nei parametri della decisione. Mai il modello di un altro canale: il corpo di una email finirebbe
     * in un SMS (RF-77, RF-132).
     */
    static MessageTemplate.Rendered render(TemplateSource templates, String templateId, String channel, String action,
                                           String reference, Map<String, Object> params, Map<String, Object> data) {
        MessageTemplate.Channel wanted = templateChannel(channel);
        var template = templates.find(templateId, wanted, String.valueOf(params.getOrDefault("locale", "it")));
        if (template.isPresent()) return template.get().render(data);
        String subject = Objects.toString(params.get("title"), reference == null ? action : reference);
        String body = Objects.toString(params.get("body"), Objects.toString(params.get("text"), ""));
        return new MessageTemplate.Rendered(wanted, subject, body);
    }

    private boolean alreadyDelivered(String decisionId, String action, String reference) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM notifier.delivery_log WHERE decision_id = ? AND action = ? AND coalesce(reference,'') = coalesce(?,'') AND status IN ('SENT','PRESENTED','QUEUED')", Integer.class, decisionId, action, reference);
        return n != null && n > 0;
    }

    private int deliveriesToday(String memberId, String channel) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM notifier.delivery_log WHERE member_id = ? AND channel = ? AND status IN ('SENT','PRESENTED') AND created_at >= date_trunc('day', now() AT TIME ZONE 'Europe/Rome') AT TIME ZONE 'Europe/Rome'", Integer.class, memberId, channel);
        return n == null ? 0 : n;
    }

    private void record(ChannelAdapter.Delivery d, ChannelAdapter.Result r) {
        String params;
        try { params = MAPPER.writeValueAsString(d.params() == null ? Map.of() : d.params()); }
        catch (Exception e) { params = "{}"; }
        jdbc.update("""
                INSERT INTO notifier.delivery_log(delivery_id, member_id, decision_id, action, reference, channel, status, detail,
                                                  provider_ref, params, expires_at, correlation_id, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?) ON CONFLICT (delivery_id) DO NOTHING""",
                d.deliveryId(), d.memberId(), d.decisionId(), d.action(), d.reference(), d.channel(), r.status(),
                r.detail() == null ? null : r.detail().substring(0, Math.min(500, r.detail().length())), r.providerRef(),
                params, d.expiresAt() == null ? null : Timestamp.from(d.expiresAt()), d.correlationId(), Timestamp.from(Instant.now()));
    }

    // ---- reinvio manuale (RF-132) --------------------------------------------------------------------------------

    /** Consegne fallite e non ancora rispedite: è la coda che l'operatore vede in console. */
    public List<Map<String, Object>> failed(int limit) {
        return jdbc.queryForList("""
                SELECT delivery_id, member_id, decision_id, action, reference, channel, detail, created_at
                FROM notifier.delivery_log WHERE status = 'FAILED' AND resent_at IS NULL
                ORDER BY created_at DESC LIMIT ?""", Math.min(Math.max(limit, 1), 500));
    }

    /**
     * Rispedisce una consegna fallita. Il testo non è conservato: si ri-renderizza dal modello con i parametri
     * registrati, così il reinvio usa la versione corrente del messaggio invece di ripetere quella vecchia.
     * L'operatore può imporre un canale diverso da quello che aveva fallito.
     *
     * @return esito della nuova consegna
     */
    @SuppressWarnings("unchecked")
    public ChannelAdapter.Result resend(String deliveryId, String channel, String operator) {
        var rows = jdbc.queryForList("""
                SELECT member_id, decision_id, action, reference, channel, status, params::text AS params, expires_at, correlation_id, resent_at
                FROM notifier.delivery_log WHERE delivery_id = ?""", deliveryId);
        if (rows.isEmpty()) throw new IllegalArgumentException("consegna sconosciuta: " + deliveryId);
        Map<String, Object> row = rows.get(0);
        if (row.get("resent_at") != null) throw new IllegalStateException("consegna già rispedita");

        Map<String, Object> params;
        try { params = row.get("params") == null ? Map.of() : MAPPER.readValue(String.valueOf(row.get("params")), Map.class); }
        catch (Exception e) { params = Map.of(); }
        Instant expires = row.get("expires_at") instanceof Timestamp ts ? ts.toInstant() : null;
        if (expires != null && expires.isBefore(Instant.now())) throw new IllegalStateException("consegna scaduta: non ha più senso rispedirla");

        String preferito = channel == null || channel.isBlank() ? String.valueOf(row.get("channel")) : channel;
        var res = deliver(String.valueOf(row.get("member_id")), str(row.get("decision_id")), String.valueOf(row.get("action")),
                str(row.get("reference")), preferito, params, expires, str(row.get("correlation_id")), Instant.now(), true);

        jdbc.update("UPDATE notifier.delivery_log SET resent_at = now(), detail = coalesce(detail, '') || ' | rispedita da ' || ? WHERE delivery_id = ?",
                operator == null ? "console" : operator, deliveryId);
        jdbc.update("UPDATE notifier.delivery_log SET resent_from = ? WHERE delivery_id <> ? AND resent_from IS NULL AND member_id = ? AND action = ? AND created_at >= now() - interval '1 minute'",
                deliveryId, deliveryId, String.valueOf(row.get("member_id")), String.valueOf(row.get("action")));
        metrics.delivery(preferito, "RESENT:" + res.status());
        return res;
    }

    private void publish(ChannelAdapter.Delivery d, ChannelAdapter.Result r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("deliveryId", d.deliveryId()); p.put("memberId", d.memberId()); p.put("decisionId", d.decisionId()); p.put("action", d.action());
        p.put("reference", d.reference()); p.put("channel", d.channel()); p.put("status", r.status()); p.put("providerRef", r.providerRef());
        p.put("expiresAt", d.expiresAt() == null ? null : d.expiresAt().toString());
        CanonicalEvents.withCorrelation(d.correlationId(), () -> {
            var ce = CanonicalEvents.of(EventTypes.DELIVERY_V1, SOURCE, "member:" + d.memberId(), p);
            kafka.send(EventTypes.TOPIC_DELIVERIES, d.memberId(), CanonicalEvents.serialize(ce));
        });
    }

    private void emitAction(ChannelAdapter.Delivery d, ChannelAdapter.Result r, String correlationId) {
        String type = switch (d.action()) { case "SHOW_OFFER" -> EventTypes.ACTION_OFFER_PRESENTED; case "ASK_FOR_FEEDBACK" -> EventTypes.ACTION_FEEDBACK_REQUESTED; default -> EventTypes.ACTION_MESSAGE_SENT; };
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(EventTypes.ATTR_CHANNEL, d.channel()); attrs.put("reference", d.reference()); attrs.put("decisionId", d.decisionId()); attrs.put("deliveryId", d.deliveryId());
        var action = new RewardingAction(type, "delivery:" + d.deliveryId(), d.decisionId(), Instant.now(), null, attrs);
        CanonicalEvents.withCorrelation(correlationId, () -> kafka.send(EventTypes.TOPIC_ACTIONS, d.memberId(), CanonicalEvents.serialize(CanonicalEvents.action(SOURCE, d.memberId(), action))));
    }

    /** Accettazione di un'offerta dall'inbox: azione OFFER_ACCEPTED, che campagne e previsioni usano (offerPropensity). */
    public void accepted(String memberId, String decisionId, String reference, String channel) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(EventTypes.ATTR_CHANNEL, channel); attrs.put("reference", reference); attrs.put("decisionId", decisionId);
        var action = new RewardingAction(EventTypes.ACTION_OFFER_ACCEPTED, "offer-accepted:" + decisionId + ":" + reference, decisionId, Instant.now(), null, attrs);
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.action(SOURCE, memberId, action)));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
