package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.domain.TemplateEngine;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Gestione dei template (BO-19; F-MSG-02) con audit, e anteprima renderizzata su un evento campione. */
@Service
public class TemplateAdminService {

    /** Codice leggibile (docs/06 §2). */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,39}$");

    public record TemplateRequest(String code, String name, String channel, String titleTpl, String bodyTpl, String icon,
                                  String linkTarget, String category, Long version) {
    }

    /**
     * Anteprima: {@code sampleEvent} è un CloudEvent (anche parziale: basta {@code data}); il membro è {@code memberId}
     * o quello del {@code subject}. {@code titleTpl}/{@code bodyTpl} opzionali rendono una bozza non salvata.
     */
    public record RenderRequest(JsonNode sampleEvent, String memberId, String titleTpl, String bodyTpl) {
    }

    public record RenderResult(String code, String channel, String category, String icon, String linkTarget, String title,
                               String body, List<String> missing) {
    }

    private final TemplateRepository templates;
    private final MessageContexts contexts;
    private final AuditPublisher audit;

    public TemplateAdminService(TemplateRepository templates, MessageContexts contexts, AuditPublisher audit) {
        this.templates = templates;
        this.contexts = contexts;
        this.audit = audit;
    }

    public MessageTemplate get(String code) {
        return templates.find(code).orElseThrow(() -> LhException.notFound("Template non trovato: " + code));
    }

    @Transactional
    public MessageTemplate create(TemplateRequest r) {
        String code = r == null || r.code() == null ? "" : r.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw LhException.validation("TEMPLATE_INVALID", "Codice template non valido (es. MSG-POINTS-EARNED).",
                    List.of(new LhException.FieldError("code", "formato ^[A-Z][A-Z0-9-]{2,39}$")));
        }
        if (templates.find(code).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Template già esistente: " + code);
        }
        MessageTemplate t = build(code, r, null);
        String actor = ActorHolder.get().asActorString();
        templates.insert(t, actor);
        audit.record("MESSAGE_TEMPLATE", code, AuditEntry.Action.CREATE, "Creato template " + t.name(), null, snapshot(t));
        return get(code);
    }

    @Transactional
    public MessageTemplate update(String code, TemplateRequest r) {
        MessageTemplate current = get(code);
        if (r == null) {
            throw LhException.badRequest("Corpo della richiesta assente.");
        }
        if (r.code() != null && !r.code().trim().equalsIgnoreCase(current.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di un template non si modifica: " + current.code());
        }
        MessageTemplate next = build(current.code(), r, current);
        long expected = r.version() != null ? r.version() : current.version();
        if (!templates.update(next, expected, ActorHolder.get().asActorString())) {
            throw LhException.conflict("VERSION_CONFLICT", "Il template è stato modificato nel frattempo: ricarica e riprova.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff(snapshot(current), snapshot(next), before, after);
        audit.record("MESSAGE_TEMPLATE", current.code(), AuditEntry.Action.UPDATE, "Modificato template " + next.name(), before, after);
        return get(current.code());
    }

    /** Anteprima con segnaposto risolti ({@code POST /v1/message-templates/{code}/render}); non scrive nulla. */
    @Transactional(readOnly = true)
    public RenderResult render(String code, RenderRequest r) {
        MessageTemplate t = get(code);
        JsonNode sample = r == null ? null : r.sampleEvent();
        JsonNode data = sample == null ? null : sample.get("data");
        String subject = text(sample, "subject");
        String memberId = r != null && r.memberId() != null && !r.memberId().isBlank() ? r.memberId().trim()
                : subject != null && subject.startsWith("member:") ? subject.substring("member:".length()) : null;
        Instant time = null;
        String rawTime = text(sample, "time");
        if (rawTime != null) {
            try {
                time = Instant.parse(rawTime);
            } catch (RuntimeException ignored) {
                time = null;
            }
        }
        JsonNode ctx = contexts.build(memberId, data, new MessageContexts.EventMeta(text(sample, "id"),
                MessageContexts.shortType(text(sample, "type")), time, subject, text(sample, "source"),
                text(sample, "lhcorrelationid")));
        String titleTpl = r != null && r.titleTpl() != null ? r.titleTpl() : t.titleTpl();
        String bodyTpl = r != null && r.bodyTpl() != null ? r.bodyTpl() : t.bodyTpl();
        TemplateEngine.Rendered title = TemplateEngine.render(titleTpl, ctx);
        TemplateEngine.Rendered body = TemplateEngine.render(bodyTpl, ctx);
        Set<String> missing = new LinkedHashSet<>(title.missing());
        missing.addAll(body.missing());
        return new RenderResult(t.code(), t.channel(), t.category(), t.icon(), t.linkTarget(), title.text(), body.text(),
                List.copyOf(missing));
    }

    private MessageTemplate build(String code, TemplateRequest r, MessageTemplate cur) {
        String name = pick(r.name(), cur == null ? null : cur.name());
        String channel = upper(pick(r.channel(), cur == null ? "INAPP" : cur.channel()));
        String category = upper(pick(r.category(), cur == null ? null : cur.category()));
        String titleTpl = pick(r.titleTpl(), cur == null ? null : cur.titleTpl());
        String bodyTpl = pick(r.bodyTpl(), cur == null ? null : cur.bodyTpl());
        String icon = r.icon() != null ? blankToNull(r.icon()) : cur == null ? null : cur.icon();
        String link = r.linkTarget() != null ? blankToNull(r.linkTarget()) : cur == null ? null : cur.linkTarget();
        List<LhException.FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) errors.add(new LhException.FieldError("name", "obbligatorio"));
        if (!MessageTemplate.CHANNELS.contains(channel)) errors.add(new LhException.FieldError("channel", "tra " + MessageTemplate.CHANNELS));
        if (!MessageTemplate.CATEGORIES.contains(category)) errors.add(new LhException.FieldError("category", "tra " + MessageTemplate.CATEGORIES));
        if (titleTpl == null || titleTpl.isBlank()) errors.add(new LhException.FieldError("titleTpl", "obbligatorio"));
        if (bodyTpl == null || bodyTpl.isBlank()) errors.add(new LhException.FieldError("bodyTpl", "obbligatorio"));
        TemplateEngine.problems(titleTpl).forEach(p -> errors.add(new LhException.FieldError("titleTpl", p)));
        TemplateEngine.problems(bodyTpl).forEach(p -> errors.add(new LhException.FieldError("bodyTpl", p)));
        if (!errors.isEmpty()) {
            throw LhException.validation("TEMPLATE_INVALID", "Template non valido: controlla i campi evidenziati.", errors);
        }
        return new MessageTemplate(code, name.trim(), channel, titleTpl, bodyTpl, icon, link, category,
                cur == null ? 0 : cur.version(), null, null);
    }

    private static Map<String, Object> snapshot(MessageTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("channel", t.channel());
        m.put("category", t.category());
        m.put("titleTpl", t.titleTpl());
        m.put("bodyTpl", t.bodyTpl());
        m.put("icon", t.icon());
        m.put("linkTarget", t.linkTarget());
        return m;
    }

    /** Solo i campi cambiati (docs/05 §6). */
    static void diff(Map<String, Object> a, Map<String, Object> b, Map<String, Object> before, Map<String, Object> after) {
        for (String k : a.keySet()) {
            if (!Objects.equals(a.get(k), b.get(k))) {
                before.put(k, a.get(k));
                after.put(k, b.get(k));
            }
        }
    }

    private static String pick(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
