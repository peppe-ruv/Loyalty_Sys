package io.loyaltyhub.engagement.demo;

import io.loyaltyhub.common.approval.ApprovalHistoryStore;
import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.engagement.application.MessageContexts;
import io.loyaltyhub.engagement.domain.ContentItem;
import io.loyaltyhub.engagement.domain.InboxMessage;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.domain.NotificationRule;
import io.loyaltyhub.engagement.domain.TemplateEngine;
import io.loyaltyhub.engagement.domain.Theme;
import io.loyaltyhub.engagement.domain.Webhook;
import io.loyaltyhub.engagement.domain.WebhookDelivery;
import io.loyaltyhub.engagement.domain.WebhookRetry;
import io.loyaltyhub.engagement.domain.WebhookSignature;
import io.loyaltyhub.engagement.infra.ContentRepository;
import io.loyaltyhub.engagement.infra.InboxRepository;
import io.loyaltyhub.engagement.infra.MemberSnapshotRepository;
import io.loyaltyhub.engagement.infra.PopupViewRepository;
import io.loyaltyhub.engagement.infra.RuleRepository;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import io.loyaltyhub.engagement.infra.ThemeRepository;
import io.loyaltyhub.engagement.infra.WebhookDeliveryRepository;
import io.loyaltyhub.engagement.infra.WebhookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carica template, regole di notifica, snapshot dei membri, inbox storica e contenuti del CMS (M6.1) (docs/servizi/engagement-service.md §6,
 * docs/10 §7). I messaggi dell'inbox sono resi dai template del seed sul loro {@code data} (le date {@code @…} dentro
 * {@code data} sono risolte come le altre), così testi e segnaposto restano coerenti per costruzione; l'evento sorgente
 * è {@code SEED-<id>}. Un messaggio letto ha {@code read_at} un'ora dopo la consegna (mai nel futuro). Webhook (M7.2):
 * uno, disattivato, con un registro storico di consegne già chiuse ({@code OK}/{@code GAVE_UP}: nessuna parte davvero)
 * il cui corpo è il CloudEvent ricostruito e firmato col segreto del seed. Profilo {@code demo}, ripetibile via
 * {@code /v1/demo/reset}.
 */
// SPEC-GAP: Q-102 — docs/10 §7 chiede solo "un webhook, disabilitato": per non lasciare vuoto il registro consegne di
// BO-23 il seed aggiunge 5 consegne storiche (ultimi 12 giorni, dentro la pulizia dei 14) di cui 2 abbandonate.
// SPEC-GAP: Q-64 — docs/10 §7 vuole un messaggio non letto "richiesta confermata" per Sofia, ma tra gli 11 template
// + MSG-BIRTHDAY non ce n'è uno: il seed aggiunge MSG-REWARD-CONFIRMED e la regola NR-REWARD-CONFIRMED
// (reward.redemption.confirmed), oltre alle 11 regole minime.
// SPEC-GAP: Q-65 — NR-POINTS-EARNED ha la condizione data.currency = PTS: wallet.points.earned arriva anche per gli STS
// e senza filtro un acquisto produrrebbe due messaggi ("162 punti" e "130 punti").
@Component
@Profile("demo")
public class EngagementSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(EngagementSeeder.class);
    private static final String SEED_ACTOR = "system";

    private final SeedLoader seed;
    private final TemplateRepository templates;
    private final RuleRepository rules;
    private final InboxRepository inbox;
    private final MemberSnapshotRepository members;
    private final MessageContexts contexts;
    private final ContentRepository contents;
    private final PopupViewRepository popups;
    private final ThemeRepository themes;
    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository webhookDeliveries;
    private final ApprovalHistoryStore approvalHistory;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EngagementSeeder(SeedLoader seed, TemplateRepository templates, RuleRepository rules, InboxRepository inbox,
                            MemberSnapshotRepository members, MessageContexts contexts, ContentRepository contents,
                            PopupViewRepository popups, ThemeRepository themes, WebhookRepository webhooks,
                            WebhookDeliveryRepository webhookDeliveries, ApprovalHistoryStore approvalHistory,
                            ObjectMapper mapper, Clock clock) {
        this.seed = seed;
        this.templates = templates;
        this.rules = rules;
        this.inbox = inbox;
        this.members = members;
        this.contexts = contexts;
        this.contents = contents;
        this.popups = popups;
        this.themes = themes;
        this.webhooks = webhooks;
        this.webhookDeliveries = webhookDeliveries;
        this.approvalHistory = approvalHistory;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "engagement";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        webhookDeliveries.deleteAll();
        webhooks.deleteAll();
        inbox.deleteAll();
        popups.deleteAll();
        themes.deleteAll();
        contents.deleteAll();
        approvalHistory.deleteAll(ApprovalPolicy.CONTENT);
        rules.deleteAll();
        templates.deleteAll();
        members.deleteAll();

        for (JsonNode m : seed.readTree("members.json")) {
            members.upsertSeed(m.path("id").asString(), text(m, "firstName"), m.path("status").asString("ACTIVE"),
                    text(m, "tier"), date(m, "registeredAt"));
        }
        Map<String, MessageTemplate> byCode = new HashMap<>();
        for (JsonNode t : seed.readTree("message-templates.json")) {
            MessageTemplate tpl = new MessageTemplate(t.path("code").asString(), t.path("name").asString(),
                    t.path("channel").asString("INAPP"), t.path("titleTpl").asString(), t.path("bodyTpl").asString(),
                    text(t, "icon"), text(t, "linkTarget"), t.path("category").asString(), 0, null, null);
            templates.insert(tpl, SEED_ACTOR);
            byCode.put(tpl.code(), tpl);
        }
        Map<String, String> factTypeByTemplate = new HashMap<>();
        int ruleCount = 0;
        for (JsonNode r : seed.readTree("notification-rules.json")) {
            NotificationRule rule = new NotificationRule(Ulid.next(clock), r.path("code").asString(), r.path("factType").asString(),
                    r.hasNonNull("condition") ? r.get("condition") : null, r.path("templateCode").asString(),
                    r.path("enabled").asBoolean(true), 0, null, null);
            rules.insert(rule, SEED_ACTOR);
            factTypeByTemplate.putIfAbsent(rule.templateCode(), rule.factType());
            ruleCount++;
        }
        Instant now = clock.instant();
        int messages = 0;
        for (JsonNode x : seed.readTree("inbox.json")) {
            MessageTemplate t = byCode.get(x.path("templateCode").asString());
            if (t == null) {
                log.warn("Seed inbox {}: template {} assente, saltato", x.path("id").asString(), x.path("templateCode").asString());
                continue;
            }
            String memberId = x.path("memberId").asString();
            String sourceId = "SEED-" + x.path("id").asString();
            Instant at = SeedDates.resolve(x.path("at").asString(), clock);
            JsonNode data = resolveDates(x.path("data"));
            JsonNode ctx = contexts.build(memberId, data, new MessageContexts.EventMeta(sourceId,
                    factTypeByTemplate.get(t.code()), at, "member:" + memberId, null, null));
            Instant readAt = x.path("read").asBoolean(false) ? min(at.plus(Duration.ofHours(1)), now) : null;
            inbox.insertIfAbsent(new InboxMessage(Ulid.next(clock), memberId, t.code(), t.channel(),
                    TemplateEngine.renderText(t.titleTpl(), ctx), TemplateEngine.renderText(t.bodyTpl(), ctx), t.icon(),
                    t.linkTarget(), t.category(), sourceId, factTypeByTemplate.get(t.code()), null, at, readAt));
            messages++;
        }
        int contentCount = 0;
        for (JsonNode c : seed.readTree("contents.json")) {
            contents.insert(new ContentItem(Ulid.next(clock), c.path("code").asString(), c.path("kind").asString(),
                    text(c, "placement"), c.path("title").asString(), text(c, "body"), text(c, "imageUrl"), text(c, "ctaLabel"),
                    text(c, "ctaTarget"), c.path("linkType").asString("NONE"), text(c, "linkCode"), c.path("audience"),
                    date(c, "startAt"), date(c, "endAt"), c.path("priority").asInt(50), text(c, "frequency"),
                    c.path("dismissible").asBoolean(true), c.path("style"), c.path("status").asString("DRAFT"), 0, null),
                    SEED_ACTOR);
            contentCount++;
        }
        JsonNode t = seed.readTree("theme.json");
        Map<String, String> colors = new java.util.LinkedHashMap<>();
        t.path("colors").properties().forEach(e -> colors.put(e.getKey(), e.getValue().asString()));
        Map<String, String> currencies = new java.util.LinkedHashMap<>();
        t.path("currencyNames").properties().forEach(e -> currencies.put(e.getKey(), e.getValue().asString()));
        themes.save(new Theme(t.path("programName").asString(), text(t, "tagline"), text(t, "logoUrl"), colors,
                text(t, "heroTitle"), text(t, "heroSubtitle"), text(t, "fontDisplay"), currencies, 0, null), null, SEED_ACTOR);
        int[] hooks = seedWebhooks();
        log.info("Seed engagement caricato: {} template, {} regole, {} messaggi, {} contenuti, tema {}, {} webhook ({} consegne)",
                byCode.size(), ruleCount, messages, contentCount, t.path("programName").asString(), hooks[0], hooks[1]);
    }

    /** Webhook e registro storico; ritorna {numero di webhook, numero di consegne}. */
    private int[] seedWebhooks() {
        int count = 0;
        int deliveries = 0;
        for (JsonNode w : seed.readTree("webhooks.json")) {
            List<String> types = new ArrayList<>();
            w.path("factTypes").forEach(ft -> types.add(ft.asString()));
            String secret = w.path("secret").asString();
            Webhook hook = new Webhook(Ulid.next(clock), w.path("code").asString(), w.path("name").asString(),
                    w.path("url").asString(), types.stream().sorted().toList(), w.path("enabled").asBoolean(false), 0, null, null,
                    null, null, null, null);
            webhooks.insert(hook, secret, SEED_ACTOR);
            count++;
            for (JsonNode d : w.path("deliveries")) {
                Instant at = SeedDates.resolve(d.path("at").asString(), clock);
                String factType = d.path("factType").asString();
                String eventId = d.path("eventId").asString();
                String memberId = text(d, "memberId");
                ObjectNode event = mapper.createObjectNode();
                event.put("specversion", "1.0");
                event.put("id", eventId);
                event.put("source", d.path("source").asString("urn:loyaltyhub:service:engagement"));
                event.put("type", io.loyaltyhub.common.event.LhEventTypes.Fact.PREFIX + factType);
                if (memberId != null) {
                    event.put("subject", "member:" + memberId);
                }
                event.put("time", at.toString());
                event.put("datacontenttype", "application/json");
                event.put("dataschema", "urn:loyaltyhub:schema:fact." + factType + ":1");
                event.put("lhtenant", "aurora");
                event.put("lhcorrelationid", eventId);
                event.put("lhhop", 0);
                event.set("data", resolveDates(d.path("data")));
                String payload = mapper.writeValueAsString(event);
                int attempts = d.path("attempts").asInt(1);
                // Ultimo tentativo: la somma dei ritenti già trascorsi (1, 5, 15 min) dopo il primo invio.
                Instant last = at;
                for (int i = 0; i < attempts - 1 && i < WebhookRetry.DELAYS.size(); i++) {
                    last = last.plus(WebhookRetry.DELAYS.get(i));
                }
                String status = d.path("status").asString("OK");
                Integer http = d.hasNonNull("httpStatus") ? d.get("httpStatus").asInt() : null;
                String error = text(d, "error");
                if (error == null && !"OK".equals(status)) {
                    error = "HTTP_ERROR";
                }
                webhookDeliveries.insertSeed(new WebhookDelivery(Ulid.next(clock), hook.id(), eventId, factType, memberId,
                        d.path("test").asBoolean(false), payload, WebhookSignature.sign(secret, payload), attempts,
                        WebhookRetry.MAX_ATTEMPTS, status, http, text(d, "responseExcerpt"), error,
                        d.hasNonNull("durationMs") ? d.get("durationMs").asInt() : null, null, min(last, clock.instant()), at));
                deliveries++;
            }
        }
        return new int[]{count, deliveries};
    }

    /** Copia di {@code data} con le espressioni di data ({@code @today+12d}, docs/10 §1) risolte in istanti ISO. */
    private JsonNode resolveDates(JsonNode data) {
        if (data == null || !data.isObject()) {
            return data;
        }
        ObjectNode copy = (ObjectNode) data.deepCopy();
        for (Map.Entry<String, JsonNode> e : data.properties()) {
            JsonNode v = e.getValue();
            if (v.isString() && v.asString().startsWith("@")) {
                copy.put(e.getKey(), SeedDates.resolve(v.asString(), clock).toString());
            }
        }
        return copy;
    }

    private Instant date(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : SeedDates.resolve(v, clock);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
