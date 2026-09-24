package io.loyaltyhub.engagement.demo;

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
import io.loyaltyhub.engagement.infra.ContentRepository;
import io.loyaltyhub.engagement.infra.InboxRepository;
import io.loyaltyhub.engagement.infra.MemberSnapshotRepository;
import io.loyaltyhub.engagement.infra.RuleRepository;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Carica template, regole di notifica, snapshot dei membri, inbox storica e contenuti del CMS (M6.1) (docs/servizi/engagement-service.md §6,
 * docs/10 §7). I messaggi dell'inbox sono resi dai template del seed sul loro {@code data} (le date {@code @…} dentro
 * {@code data} sono risolte come le altre), così testi e segnaposto restano coerenti per costruzione; l'evento sorgente
 * è {@code SEED-<id>}. Un messaggio letto ha {@code read_at} un'ora dopo la consegna (mai nel futuro). Profilo
 * {@code demo}, ripetibile via {@code /v1/demo/reset}.
 */
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
    private final Clock clock;

    public EngagementSeeder(SeedLoader seed, TemplateRepository templates, RuleRepository rules, InboxRepository inbox,
                            MemberSnapshotRepository members, MessageContexts contexts, ContentRepository contents,
                            Clock clock) {
        this.seed = seed;
        this.templates = templates;
        this.rules = rules;
        this.inbox = inbox;
        this.members = members;
        this.contexts = contexts;
        this.contents = contents;
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
        inbox.deleteAll();
        contents.deleteAll();
        rules.deleteAll();
        templates.deleteAll();
        members.deleteAll();

        for (JsonNode m : seed.readTree("members.json")) {
            members.upsertSeed(m.path("id").asString(), text(m, "firstName"), m.path("status").asString("ACTIVE"),
                    text(m, "tier"));
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
        log.info("Seed engagement caricato: {} template, {} regole, {} messaggi, {} contenuti", byCode.size(), ruleCount,
                messages, contentCount);
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
