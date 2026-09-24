package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Codes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.engagement.domain.DataCondition;
import io.loyaltyhub.engagement.domain.NotificationRule;
import io.loyaltyhub.engagement.infra.RuleRepository;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Gestione delle regole di notifica fatto → template (BO-19; F-MSG-01) con audit. Il tipo di fatto è uno del catalogo
 * di docs/05 §5 (forma breve o completa), mai {@code message.delivered}; la condizione legge solo {@code data.*}.
 */
@Service
public class RuleAdminService {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,39}$");

    /** Tipi di fatto ammessi (forma breve), dal catalogo {@link LhEventTypes.Fact}; esclusi quelli che darebbero cicli. */
    public static final Set<String> FACT_TYPES = factTypes();

    /**
     * {@code condition}: assente = invariata (in modifica); {@code null} o {@code {}} = nessuna condizione.
     * {@code code} opzionale in creazione (generato {@code NR-…}).
     */
    public record RuleRequest(String code, String factType, JsonNode condition, String templateCode, Boolean enabled,
                              Long version) {
    }

    private final RuleRepository rules;
    private final TemplateRepository templates;
    private final AuditPublisher audit;
    private final Clock clock;

    public RuleAdminService(RuleRepository rules, TemplateRepository templates, AuditPublisher audit, Clock clock) {
        this.rules = rules;
        this.templates = templates;
        this.audit = audit;
        this.clock = clock;
    }

    public NotificationRule get(String idOrCode) {
        return rules.find(idOrCode).orElseThrow(() -> LhException.notFound("Regola non trovata: " + idOrCode));
    }

    @Transactional
    public NotificationRule create(RuleRequest r) {
        if (r == null) {
            throw LhException.badRequest("Corpo della richiesta assente.");
        }
        String code = r.code() == null || r.code().isBlank() ? "NR-" + Codes.random(8) : r.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw LhException.validation("RULE_INVALID", "Codice regola non valido (es. NR-POINTS-EARNED).",
                    List.of(new LhException.FieldError("code", "formato ^[A-Z][A-Z0-9-]{2,39}$")));
        }
        if (rules.find(code).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Regola già esistente: " + code);
        }
        NotificationRule rule = build(Ulid.next(clock), code, r, null);
        rules.insert(rule, ActorHolder.get().asActorString());
        audit.record("NOTIFICATION_RULE", code, AuditEntry.Action.CREATE,
                "Creata regola " + rule.factType() + " → " + rule.templateCode(), null, snapshot(rule));
        return get(rule.id());
    }

    @Transactional
    public NotificationRule update(String idOrCode, RuleRequest r) {
        NotificationRule current = get(idOrCode);
        if (r == null) {
            throw LhException.badRequest("Corpo della richiesta assente.");
        }
        if (r.code() != null && !r.code().trim().equalsIgnoreCase(current.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di una regola non si modifica: " + current.code());
        }
        NotificationRule next = build(current.id(), current.code(), r, current);
        long expected = r.version() != null ? r.version() : current.version();
        if (!rules.update(next, expected, ActorHolder.get().asActorString())) {
            throw LhException.conflict("VERSION_CONFLICT", "La regola è stata modificata nel frattempo: ricarica e riprova.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        TemplateAdminService.diff(snapshot(current), snapshot(next), before, after);
        String summary = current.enabled() != next.enabled()
                ? (next.enabled() ? "Attivata" : "Disattivata") + " regola " + current.code()
                : "Modificata regola " + current.code();
        audit.record("NOTIFICATION_RULE", current.code(), AuditEntry.Action.UPDATE, summary, before, after);
        return get(current.id());
    }

    /** Forma breve del tipo di fatto ({@code io.loyaltyhub.fact.x.y} → {@code x.y}). */
    public static String normalizeFactType(String type) {
        if (type == null) {
            return null;
        }
        String t = type.trim();
        return t.startsWith(LhEventTypes.Fact.PREFIX) ? t.substring(LhEventTypes.Fact.PREFIX.length()) : t;
    }

    private NotificationRule build(String id, String code, RuleRequest r, NotificationRule cur) {
        String factType = r.factType() != null ? normalizeFactType(r.factType()) : cur == null ? null : cur.factType();
        String templateCode = r.templateCode() != null ? r.templateCode().trim().toUpperCase() : cur == null ? null : cur.templateCode();
        JsonNode condition = r.condition() != null ? emptyToNull(r.condition()) : cur == null ? null : cur.condition();
        boolean enabled = r.enabled() != null ? r.enabled() : cur == null || cur.enabled();
        List<LhException.FieldError> errors = new ArrayList<>();
        if (factType == null || factType.isBlank()) {
            errors.add(new LhException.FieldError("factType", "obbligatorio"));
        } else if ("message.delivered".equals(factType)) {
            errors.add(new LhException.FieldError("factType", "message.delivered non può essere oggetto di regole (eviterebbe cicli)"));
        } else if (!FACT_TYPES.contains(factType)) {
            errors.add(new LhException.FieldError("factType", "tipo di fatto sconosciuto: " + factType));
        }
        if (templateCode == null || templateCode.isBlank()) {
            errors.add(new LhException.FieldError("templateCode", "obbligatorio"));
        } else if (templates.find(templateCode).isEmpty()) {
            errors.add(new LhException.FieldError("templateCode", "template inesistente: " + templateCode));
        }
        DataCondition.problems(condition).forEach(p -> errors.add(new LhException.FieldError("condition", p)));
        if (!errors.isEmpty()) {
            throw LhException.validation("RULE_INVALID", "Regola non valida: controlla i campi evidenziati.", errors);
        }
        return new NotificationRule(id, code, factType, condition, templateCode, enabled, cur == null ? 0 : cur.version(),
                null, null);
    }

    private static JsonNode emptyToNull(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || (n.isObject() && n.isEmpty()) ? null : n;
    }

    private static Map<String, Object> snapshot(NotificationRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factType", r.factType());
        m.put("templateCode", r.templateCode());
        m.put("condition", r.condition() == null ? null : r.condition().toString());
        m.put("enabled", r.enabled());
        return m;
    }

    private static Set<String> factTypes() {
        Set<String> out = new TreeSet<>();
        for (Field f : LhEventTypes.Fact.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class && !"PREFIX".equals(f.getName())) {
                try {
                    out.add(normalizeFactType((String) f.get(null)));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        out.remove("message.delivered");
        return Collections.unmodifiableSet(out);
    }
}
