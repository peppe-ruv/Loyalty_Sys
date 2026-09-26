package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Codes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.engagement.domain.Webhook;
import io.loyaltyhub.engagement.domain.WebhookDelivery;
import io.loyaltyhub.engagement.domain.WebhookRetry;
import io.loyaltyhub.engagement.domain.WebhookSignature;
import io.loyaltyhub.engagement.infra.WebhookDeliveryRepository;
import io.loyaltyhub.engagement.infra.WebhookHttpSender;
import io.loyaltyhub.engagement.infra.WebhookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Webhook in uscita (docs/servizi/engagement-service.md §3, §5; F-WBH-01, BO-23): gestione con audit (solo ADMIN,
 * capacità {@code webhook.write} di docs/08 §2), abbinamento dei fatti alle sottoscrizioni, evento di prova e
 * *Riprova*. Il segreto si genera qui e si restituisce una sola volta, alla creazione.
 * <p>Abbinamento ({@link #enqueue}): dentro la transazione idempotente del consumer si scrivono solo le righe
 * {@code webhook_delivery} (corpo = CloudEvent originale, firmato); l'HTTP parte dopo, dal {@link WebhookDispatcher}.
 * {@code message.delivered} non è mai consegnato (evita cicli).
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,39}$");
    static final int NAME_MAX = 80;

    /**
     * Creazione e modifica. In modifica un campo {@code null} resta invariato; {@code code} opzionale in creazione
     * (generato {@code WH-…}) e immutabile.
     */
    public record WebhookRequest(String code, String name, String url, List<String> factTypes, Boolean enabled, Long version) {
    }

    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookDispatcher dispatcher;
    private final WebhookHttpSender sender;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WebhookService(WebhookRepository webhooks, WebhookDeliveryRepository deliveries, WebhookDispatcher dispatcher,
                          WebhookHttpSender sender, AuditPublisher audit, ObjectMapper mapper, TransactionTemplate tx,
                          Clock clock) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
        this.sender = sender;
        this.audit = audit;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------- abbinamento dei fatti ----------

    /**
     * Una consegna {@code PENDING} per ogni webhook attivo abbonato al tipo del fatto. Idempotente per (webhook, evento):
     * un fatto rielaborato non duplica. Va chiamato dentro la transazione del consumer.
     */
    public int enqueue(LhEvent<JsonNode> event) {
        String factType = RuleAdminService.normalizeFactType(event.type());
        if (factType == null || event.id() == null || !event.type().startsWith(LhEventTypes.Fact.PREFIX)
                || LhEventTypes.Fact.MESSAGE_DELIVERED.equals(event.type())) {
            return 0;
        }
        List<WebhookRepository.Target> targets = webhooks.findEnabledFor(factType);
        if (targets.isEmpty()) {
            return 0;
        }
        String payload = mapper.writeValueAsString(event);
        Instant now = clock.instant();
        int created = 0;
        for (WebhookRepository.Target t : targets) {
            if (deliveries.insertIfAbsent(Ulid.next(clock), t.id(), event.id(), factType, event.memberId(), false, payload,
                    WebhookSignature.sign(t.secret(), payload), now)) {
                created++;
            }
        }
        return created;
    }

    // ---------- gestione ----------

    public List<Webhook> list() {
        return webhooks.findAll();
    }

    public Webhook get(String idOrCode) {
        return webhooks.find(idOrCode).orElseThrow(() -> LhException.notFound("Webhook non trovato: " + idOrCode));
    }

    /** Crea il webhook e restituisce, solo questa volta, il segreto di firma. */
    @Transactional
    public Webhook create(WebhookRequest r) {
        if (r == null) {
            throw LhException.badRequest("Corpo della richiesta assente.");
        }
        String code = r.code() == null || r.code().isBlank() ? "WH-" + Codes.random(6) : r.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw LhException.validation("WEBHOOK_INVALID", "Codice webhook non valido (es. WH-CRM).",
                    List.of(new LhException.FieldError("code", "formato ^[A-Z][A-Z0-9-]{2,39}$")));
        }
        if (webhooks.codeTaken(code)) {
            throw LhException.conflict("CODE_TAKEN", "Webhook già esistente: " + code);
        }
        Webhook w = build(Ulid.next(clock), code, r, null);
        String secret = WebhookSignature.newSecret();
        webhooks.insert(w, secret, ActorHolder.get().asActorString());
        audit.record("WEBHOOK", code, AuditEntry.Action.CREATE, "Creato webhook " + code + " verso " + w.url(), null, snapshot(w));
        return get(w.id()).withSecret(secret);
    }

    @Transactional
    public Webhook update(String idOrCode, WebhookRequest r) {
        Webhook current = get(idOrCode);
        if (r == null) {
            throw LhException.badRequest("Corpo della richiesta assente.");
        }
        if (r.code() != null && !r.code().trim().equalsIgnoreCase(current.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di un webhook non si modifica: " + current.code());
        }
        Webhook next = build(current.id(), current.code(), r, current);
        long expected = r.version() != null ? r.version() : current.version();
        if (!webhooks.update(next, expected, ActorHolder.get().asActorString())) {
            throw LhException.conflict("VERSION_CONFLICT", "Il webhook è stato modificato nel frattempo: ricarica e riprova.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        TemplateAdminService.diff(snapshot(current), snapshot(next), before, after);
        String summary = current.enabled() != next.enabled() && before.size() == 1
                ? (next.enabled() ? "Attivato" : "Disattivato") + " webhook " + current.code()
                : "Modificato webhook " + current.code();
        audit.record("WEBHOOK", current.code(), AuditEntry.Action.UPDATE, summary, before, after);
        return get(current.id());
    }

    /** Elimina il webhook e il suo registro consegne. */
    @Transactional
    public void delete(String idOrCode) {
        Webhook current = get(idOrCode);
        webhooks.delete(current.id());
        audit.record("WEBHOOK", current.code(), AuditEntry.Action.DELETE, "Eliminato webhook " + current.code(),
                snapshot(current), null);
    }

    public PageResponse<WebhookDelivery> deliveries(String idOrCode, String status, int page, int size) {
        Webhook w = get(idOrCode);
        if (status != null && !status.isBlank()) {
            try {
                WebhookRetry.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw LhException.badRequest("Stato di consegna sconosciuto: " + status);
            }
        }
        io.loyaltyhub.common.web.PageParams paging = io.loyaltyhub.common.web.PageParams.of(page, size); // SPEC-GAP: Q-332
        int p = paging.page();
        int s = paging.size();
        return PageResponse.of(deliveries.page(w.id(), status, p, s), p, s, deliveries.count(w.id(), status));
    }

    public WebhookDelivery delivery(String id) {
        return deliveries.find(id).orElseThrow(() -> LhException.notFound("Consegna non trovata: " + id));
    }

    /**
     * "Invia evento di prova" (BO-23): consegna marcata {@code test} con il fatto d'esempio del contratto per il primo
     * tipo sottoscritto (id e ora nuovi), tentata subito; se fallisce segue i ritenti come le altre. Vale anche per un
     * webhook disattivato: è un'azione esplicita di chi lo configura.
     */
    // SPEC-GAP: Q-100 — docs/08 non dice che cosa contiene l'evento di prova: qui è l'esempio del contratto
    // (contracts/events/examples/fact.<tipo>.json) del primo tipo sottoscritto, con id/ora/correlazione nuovi e
    // `lhactor` di chi lo invia; il registro lo marca `test` (nessun fatto reale viene pubblicato su Kafka).
    public WebhookDelivery test(String idOrCode) {
        Webhook w = get(idOrCode);
        String actor = ActorHolder.get().asActorString();
        String deliveryId = tx.execute(status -> {
            String secret = webhooks.secretOf(w.id()).orElseThrow(() -> LhException.notFound("Webhook non trovato: " + idOrCode));
            String factType = w.factTypes().isEmpty() ? "member.updated" : w.factTypes().getFirst();
            String eventId = Ulid.next(clock);
            ObjectNode event = sampleEvent(factType, eventId, actor);
            String payload = mapper.writeValueAsString(event);
            String subject = event.path("subject").asString("");
            String memberId = subject.startsWith("member:") ? subject.substring("member:".length()) : null;
            String id = Ulid.next(clock);
            deliveries.insertIfAbsent(id, w.id(), eventId, factType, memberId, true, payload,
                    WebhookSignature.sign(secret, payload), clock.instant());
            audit.record("WEBHOOK", w.code(), AuditEntry.Action.UPDATE, "Inviato evento di prova " + factType + " al webhook "
                    + w.code(), null, Map.of("deliveryId", id, "eventId", eventId));
            return id;
        });
        return dispatcher.attemptNow(deliveryId, List.of(WebhookRetry.Status.PENDING.name()))
                .orElseGet(() -> delivery(deliveryId));
    }

    /** *Riprova* (BO-23): un tentativo immediato su una consegna {@code FAILED} o {@code GAVE_UP}. */
    public WebhookDelivery retry(String deliveryId) {
        WebhookDelivery d = delivery(deliveryId);
        if (!WebhookRetry.isRetryable(d.status())) {
            throw LhException.conflict("DELIVERY_NOT_RETRYABLE",
                    "Si ritentano solo le consegne fallite o abbandonate (stato attuale: " + d.status() + ").");
        }
        tx.executeWithoutResult(status -> audit.record("WEBHOOK_DELIVERY", d.id(), AuditEntry.Action.UPDATE,
                "Ritentata a mano la consegna " + d.id() + " (" + d.factType() + ")", Map.of("status", d.status()), null));
        return dispatcher.attemptNow(d.id(), List.of(WebhookRetry.Status.FAILED.name(), WebhookRetry.Status.GAVE_UP.name()))
                .orElseThrow(() -> LhException.conflict("DELIVERY_BUSY", "La consegna è già in invio: attendi qualche secondo e ricarica."));
    }

    /** Pulizia (docs/servizi/engagement-service.md §5): consegne più vecchie di 14 giorni. */
    public int purgeDeliveries(Instant before) {
        return deliveries.deleteOlderThan(before);
    }

    // ---------- interni ----------

    private Webhook build(String id, String code, WebhookRequest r, Webhook cur) {
        String name = r.name() != null ? r.name().trim() : cur == null ? null : cur.name();
        String url = r.url() != null ? r.url().trim() : cur == null ? null : cur.url();
        List<String> types = r.factTypes() != null ? normalizeTypes(r.factTypes()) : cur == null ? List.of() : cur.factTypes();
        boolean enabled = r.enabled() != null ? r.enabled() : cur == null || cur.enabled();
        List<LhException.FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new LhException.FieldError("name", "obbligatorio"));
        } else if (name.length() > NAME_MAX) {
            errors.add(new LhException.FieldError("name", "al massimo " + NAME_MAX + " caratteri"));
        }
        Optional<String> urlProblem = sender.policy().problem(url);
        urlProblem.ifPresent(p -> errors.add(new LhException.FieldError("url", p)));
        if (types.isEmpty()) {
            errors.add(new LhException.FieldError("factTypes", "almeno un tipo di fatto"));
        }
        for (String t : types) {
            if ("message.delivered".equals(t)) {
                errors.add(new LhException.FieldError("factTypes", "message.delivered non si consegna ai webhook (eviterebbe cicli)"));
            } else if (!RuleAdminService.FACT_TYPES.contains(t)) {
                errors.add(new LhException.FieldError("factTypes", "tipo di fatto sconosciuto: " + t));
            }
        }
        if (!errors.isEmpty()) {
            throw LhException.validation("WEBHOOK_INVALID", "Webhook non valido: controlla i campi evidenziati.", errors);
        }
        return new Webhook(id, code, name, url, types, enabled, cur == null ? 0 : cur.version(), null, null, null, null, null, null);
    }

    private static List<String> normalizeTypes(List<String> raw) {
        TreeSet<String> out = new TreeSet<>();
        for (String t : raw) {
            if (t != null && !t.isBlank()) {
                out.add(RuleAdminService.normalizeFactType(t));
            }
        }
        return List.copyOf(out);
    }

    /** Esempio del contratto per il tipo (o un fatto minimo se manca), con id, ora e correlazione nuovi. */
    private ObjectNode sampleEvent(String factType, String eventId, String actor) {
        ObjectNode event = null;
        ClassPathResource sample = new ClassPathResource("webhook-samples/fact." + factType + ".json");
        if (sample.exists()) {
            try (InputStream in = sample.getInputStream()) {
                JsonNode n = mapper.readTree(in);
                if (n.isObject()) {
                    event = (ObjectNode) n;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Esempio del fatto {} illeggibile: uso un evento minimo", factType, e);
            }
        }
        if (event == null) {
            event = mapper.createObjectNode();
            event.put("specversion", LhEvent.SPEC_VERSION);
            event.put("source", "urn:loyaltyhub:service:engagement");
            event.put("type", LhEventTypes.Fact.PREFIX + factType);
            event.put("subject", "member:MBR-000002");
            event.put("datacontenttype", LhEvent.DATA_CONTENT_TYPE);
            event.put("lhtenant", LhEvent.TENANT);
            event.set("data", mapper.createObjectNode());
        }
        event.put("id", eventId);
        event.put("time", clock.instant().toString());
        event.put("lhcorrelationid", eventId);
        event.remove("lhcausationid");
        event.put("lhhop", 0);
        event.put("lhactor", actor);
        return event;
    }

    private static Map<String, Object> snapshot(Webhook w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.name());
        m.put("url", w.url());
        m.put("factTypes", String.join(", ", w.factTypes()));
        m.put("enabled", w.enabled());
        return m;
    }
}
