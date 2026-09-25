package io.loyaltyhub.campaign.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.campaign.api.CampaignSummary;
import io.loyaltyhub.campaign.api.CreateCampaignRequest;
import io.loyaltyhub.campaign.api.PortalCampaignView;
import io.loyaltyhub.campaign.api.SimulateRequest;
import io.loyaltyhub.campaign.api.TransitionRequest;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import io.loyaltyhub.campaign.engine.CampaignEngine;
import io.loyaltyhub.campaign.engine.EvalAction;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import io.loyaltyhub.campaign.infra.CampaignRepository;
import io.loyaltyhub.campaign.infra.CounterRepository;
import io.loyaltyhub.campaign.infra.EvaluationLogRepository;
import io.loyaltyhub.campaign.infra.EvaluationLogRepository.DailyStat;
import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import io.loyaltyhub.common.approval.ApprovalAction;
import io.loyaltyhub.common.approval.ApprovalHistory;
import io.loyaltyhub.common.approval.ApprovalHistoryStore;
import io.loyaltyhub.common.approval.ApprovalItem;
import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalRule;
import io.loyaltyhub.common.approval.ApprovalSource;
import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.approval.GovernedTransitions;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Gestione delle campagne (docs/servizi/campaign-service.md §3): elenco, creazione, transizioni, validazione, portale, simulazione. */
@Service
public class CampaignAdminService implements ApprovalSource {

    /** Formato del {@code code} delle entità di configurazione (docs/06 §2). */
    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,39}$");
    private static final int CODE_MAX = 40;

    private final CampaignRepository campaigns;
    private final CounterRepository counters;
    private final EvaluationLogRepository evaluations;
    private final MemberSnapshotRepository snapshots;
    private final CampaignCache cache;
    private final CampaignEngine engine;
    private final EvaluationService evaluation;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApprovalPolicy policy;
    private final ApprovalHistoryStore history;

    public CampaignAdminService(CampaignRepository campaigns, CounterRepository counters,
                                EvaluationLogRepository evaluations, MemberSnapshotRepository snapshots,
                                CampaignCache cache, CampaignEngine engine,
                                EvaluationService evaluation, LhEventFactory events, OutboxWriter outbox,
                                AuditPublisher audit, ObjectMapper mapper, Clock clock,
                                ApprovalPolicy policy, ApprovalHistoryStore history) {
        this.campaigns = campaigns;
        this.counters = counters;
        this.evaluations = evaluations;
        this.snapshots = snapshots;
        this.cache = cache;
        this.engine = engine;
        this.evaluation = evaluation;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.policy = policy;
        this.history = history;
    }

    // ---------- letture ----------

    public List<CampaignSummary> list(String status, String actionType, String q) {
        return campaigns.search(status, actionType, q).stream()
                .map(c -> {
                    CounterRepository.Totals t = counters.totals(c.id());
                    return CampaignSummary.of(c, t, budgetOf(c.limits(), t.matches(), t.pointsDecided()));
                }).toList();
    }

    /** Campagna per {@code id} o per {@code code}: nei path si accetta indifferentemente l'uno o l'altro (docs/06 §2). */
    public Campaign get(String idOrCode) {
        return campaigns.findById(idOrCode).or(() -> campaigns.findByCode(idOrCode))
                .orElseThrow(() -> LhException.notFound("Campagna non trovata: " + idOrCode));
    }

    /** Statistiche della campagna (F-CMP-10, docs §3): totali, budget residuo e serie giornaliera 30 giorni. */
    public CampaignStats stats(String idOrCode) {
        Campaign c = get(idOrCode);
        CounterRepository.Totals t = counters.totals(c.id());
        long uniqueMembers = counters.uniqueMembers(c.id());
        Budget budget = budgetOf(c.limits(), t.matches(), t.pointsDecided());
        Instant from = clock.instant().minus(Duration.ofDays(30));
        List<DailyStat> daily = evaluations.dailyForCampaign(c.code(), from);
        return new CampaignStats(c.id(), c.code(), c.name(), c.status().name(),
                t.matches(), uniqueMembers, t.pointsDecided(), t.pointsGranted(), budget, daily);
    }

    /** Budget dai limiti globali ({@code limits.global.maxPoints/maxMatches}); residuo = max − consumato. */
    static Budget budgetOf(JsonNode limits, long matches, long pointsDecided) {
        JsonNode global = limits == null ? null : limits.get("global");
        if (global == null || global.isNull()) {
            return null;
        }
        Long maxPoints = global.hasNonNull("maxPoints") ? global.get("maxPoints").asLong() : null;
        Long maxMatches = global.hasNonNull("maxMatches") ? global.get("maxMatches").asLong() : null;
        if (maxPoints == null && maxMatches == null) {
            return null;
        }
        Long remainingPoints = maxPoints == null ? null : Math.max(0, maxPoints - pointsDecided);
        Long remainingMatches = maxMatches == null ? null : Math.max(0, maxMatches - matches);
        return new Budget(maxPoints, remainingPoints, maxMatches, remainingMatches);
    }

    public record Budget(Long maxPoints, Long remainingPoints, Long maxMatches, Long remainingMatches) {
    }

    public record CampaignStats(String id, String code, String name, String status,
                                long matches, long uniqueMembers, long pointsDecided, long pointsGranted,
                                Budget budget, List<DailyStat> daily) {
    }

    // ---------- scritture ----------

    @Transactional
    public Campaign create(CreateCampaignRequest r) {
        List<String> errors = validate(r);
        if (!errors.isEmpty()) {
            throw LhException.validation("CAMPAIGN_INVALID", String.join("; ", errors));
        }
        if (r.code() == null || r.code().isBlank()) {
            throw LhException.badRequest("code è obbligatorio");
        }
        if (!CODE.matcher(r.code()).matches()) {
            throw LhException.validation("INVALID_CODE", "Codice non valido: lettere maiuscole, cifre e trattini (3–40)",
                    List.of(new LhException.FieldError("code", "formato ^[A-Z][A-Z0-9-]{2,39}$")));
        }
        if (campaigns.findByCode(r.code()).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Codice campagna già esistente: " + r.code());
        }
        Campaign c = new Campaign(
                Ulid.next(clock), r.code(), r.name(), r.description(), r.memberDescription(), r.icon(),
                r.triggerActionTypes() == null ? List.of() : r.triggerActionTypes(),
                node(r.audience(), "{\"all\":true}"), node(r.conditions(), "{\"op\":\"all\",\"rules\":[]}"),
                node(r.effects(), "[]"), node(r.limits(), "{}"), node(r.schedule(), "{}"),
                r.priority() == null ? 100 : r.priority(), r.exclusiveGroup(),
                r.visibleInPortal() != null && r.visibleInPortal(), false,
                r.requiresLegal() != null && r.requiresLegal(), // BO-06 sez. 1 + docs/06 §7: requiresLegal ⇒ LEGAL
                r.labels() == null ? List.of() : r.labels(), CampaignStatus.DRAFT, 0, null, null);
        campaigns.insert(c);
        cache.reload();
        audit.record("CAMPAIGN", c.code(), AuditEntry.Action.CREATE,
                "Creata campagna " + c.name() + " (" + c.code() + ")",
                null, Map.of("code", c.code(), "name", c.name(), "status", c.status().name()));
        return c;
    }

    /**
     * Modifica ({@code PUT /v1/campaigns/{id}}, F-CMP-01; docs/03 §3.6). I campi assenti ({@code null}) restano
     * invariati; {@code code} non cambia mai. {@code DRAFT}/{@code IN_REVIEW}/{@code APPROVED}: tutto modificabile.
     * {@code LIVE} (e {@code PAUSED}, SPEC-GAP Q-51): solo i campi sicuri — nome, descrizioni, icona, priorità,
     * {@code schedule.endAt} — altrimenti {@code 409 CAMPAIGN_LIVE_LOCKED} (per il resto si duplica).
     * {@code ENDED}/{@code ARCHIVED}: {@code 409 CAMPAIGN_NOT_EDITABLE}.
     */
    // SPEC-GAP: Q-254 — requiresLegal si imposta solo alla creazione: il PUT lo ignora (toglierlo aggirerebbe LEGAL).
    @Transactional
    public Campaign update(String idOrCode, CreateCampaignRequest r) {
        Campaign c = get(idOrCode);
        String id = c.id();
        if (r.code() != null && !r.code().equals(c.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di una campagna non si modifica: " + c.code());
        }
        if (c.status() == CampaignStatus.ENDED || c.status() == CampaignStatus.ARCHIVED) {
            throw LhException.conflict("CAMPAIGN_NOT_EDITABLE", "Una campagna " + c.status() + " non si modifica.");
        }
        Campaign m = merge(c, r);
        if (c.status() == CampaignStatus.LIVE || c.status() == CampaignStatus.PAUSED) {
            List<String> locked = lockedChanges(c, m);
            if (!locked.isEmpty()) {
                throw LhException.conflict("CAMPAIGN_LIVE_LOCKED", "Su una campagna " + c.status()
                        + " si modificano solo nome, descrizioni, icona, priorità e fine calendario; campi bloccati: "
                        + String.join(", ", locked) + ". Per cambiarli duplica la campagna.");
            }
        }
        List<String> errors = validate(new CreateCampaignRequest(m.code(), m.name(), m.description(), m.memberDescription(),
                m.icon(), m.triggerActionTypes(), m.audience(), m.conditions(), m.effects(), m.limits(), m.schedule(),
                m.priority(), m.exclusiveGroup(), m.visibleInPortal(), m.labels(), null));
        if (!errors.isEmpty()) {
            throw LhException.validation("CAMPAIGN_INVALID", String.join("; ", errors));
        }
        // SPEC-GAP: Q-112 — "versioni" (M7.6) = optimistic locking con 409, niente storico delle revisioni.
        long expected = r.version() != null ? r.version() : c.version();
        if (!campaigns.update(m, expected)) {
            throw LhException.conflict("VERSION_CONFLICT", "La campagna è stata modificata nel frattempo: ricarica e riprova.");
        }
        cache.reload();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        changedFields(c, m, before, after);
        audit.record("CAMPAIGN", c.code(), AuditEntry.Action.UPDATE, "Modificata campagna " + m.name() + " (" + c.code() + ")",
                before, after);
        return campaigns.findById(id).orElseThrow();
    }

    /** Campi modificabili cambiati tra {@code a} e {@code b}: la voce di audit riporta solo quelli (docs/05 §6). */
    static void changedFields(Campaign a, Campaign b, Map<String, Object> before, Map<String, Object> after) {
        diffField(before, after, "name", a.name(), b.name());
        diffField(before, after, "description", a.description(), b.description());
        diffField(before, after, "memberDescription", a.memberDescription(), b.memberDescription());
        diffField(before, after, "icon", a.icon(), b.icon());
        diffField(before, after, "triggerActionTypes", a.triggerActionTypes(), b.triggerActionTypes());
        diffField(before, after, "audience", a.audience(), b.audience());
        diffField(before, after, "conditions", a.conditions(), b.conditions());
        diffField(before, after, "effects", a.effects(), b.effects());
        diffField(before, after, "limits", a.limits(), b.limits());
        diffField(before, after, "schedule", a.schedule(), b.schedule());
        diffField(before, after, "priority", a.priority(), b.priority());
        diffField(before, after, "exclusiveGroup", a.exclusiveGroup(), b.exclusiveGroup());
        diffField(before, after, "visibleInPortal", a.visibleInPortal(), b.visibleInPortal());
        diffField(before, after, "labels", a.labels(), b.labels());
    }

    private static void diffField(Map<String, Object> before, Map<String, Object> after, String field, Object a, Object b) {
        if (!java.util.Objects.equals(a, b)) {
            before.put(field, a);
            after.put(field, b);
        }
    }

    /**
     * Duplica ({@code POST /v1/campaigns/{id}/duplicate}, F-CMP-13, M7.6): copia regole, pubblico, limiti e calendario
     * in {@code DRAFT} con codice {@code <code>-COPY-n} (primo {@code n} libero da 1) e nome "(copia)". È il modo di
     * cambiare i campi bloccati di una campagna {@code LIVE} (docs/03 §3.6). La copia non è mai di sistema; il flag
     * {@code requiresLegal} resta (la policy si ricalcola all'invio).
     */
    // SPEC-GAP: Q-253 — se <code>-COPY-n supera i 40 caratteri del formato (docs/06 §2) si tronca la base, come Q-113.
    @Transactional
    public Campaign duplicate(String idOrCode) {
        Campaign c = get(idOrCode);
        String code = null;
        for (int n = 1; code == null || campaigns.findByCode(code).isPresent(); n++) {
            code = copyCode(c.code(), n);
        }
        Campaign copy = new Campaign(Ulid.next(clock), code, c.name() + " (copia)", c.description(), c.memberDescription(),
                c.icon(), c.triggerActionTypes(), c.audience(), c.conditions(), c.effects(), c.limits(), c.schedule(),
                c.priority(), c.exclusiveGroup(), c.visibleInPortal(), false, c.requiresLegal(), c.labels(),
                CampaignStatus.DRAFT, 0, null, null);
        campaigns.insert(copy);
        cache.reload();
        audit.record("CAMPAIGN", code, AuditEntry.Action.CREATE, "Duplicata campagna " + c.code() + " in " + code,
                null, Map.of("from", c.code(), "code", code, "status", CampaignStatus.DRAFT.name()));
        return campaigns.findByCode(code).orElseThrow();
    }

    /** {@code <code>-COPY-n} entro i 40 caratteri di {@code ^[A-Z][A-Z0-9-]{2,39}$}: si accorcia la base, mai il suffisso. */
    static String copyCode(String code, int n) {
        String suffix = "-COPY-" + n;
        int room = CODE_MAX - suffix.length();
        return (code.length() > room ? code.substring(0, room) : code) + suffix;
    }

    private Campaign merge(Campaign c, CreateCampaignRequest r) {
        return new Campaign(c.id(), c.code(),
                r.name() != null ? r.name() : c.name(),
                r.description() != null ? r.description() : c.description(),
                r.memberDescription() != null ? r.memberDescription() : c.memberDescription(),
                r.icon() != null ? r.icon() : c.icon(),
                r.triggerActionTypes() != null ? r.triggerActionTypes() : c.triggerActionTypes(),
                present(r.audience()) ? r.audience() : c.audience(),
                present(r.conditions()) ? r.conditions() : c.conditions(),
                present(r.effects()) ? r.effects() : c.effects(),
                present(r.limits()) ? r.limits() : c.limits(),
                present(r.schedule()) ? r.schedule() : c.schedule(),
                r.priority() != null ? r.priority() : c.priority(),
                r.exclusiveGroup() != null ? r.exclusiveGroup() : c.exclusiveGroup(),
                r.visibleInPortal() != null ? r.visibleInPortal() : c.visibleInPortal(),
                c.system(), c.requiresLegal(),
                r.labels() != null ? r.labels() : c.labels(),
                c.status(), c.version(), c.createdAt(), c.updatedAt());
    }

    /** Campi non "sicuri" (docs/03 §3.6) che la modifica cambierebbe. */
    private List<String> lockedChanges(Campaign before, Campaign after) {
        List<String> changed = new ArrayList<>();
        if (!before.triggerActionTypes().equals(after.triggerActionTypes())) changed.add("triggerActionTypes");
        if (!before.audience().equals(after.audience())) changed.add("audience");
        if (!before.conditions().equals(after.conditions())) changed.add("conditions");
        if (!before.effects().equals(after.effects())) changed.add("effects");
        if (!before.limits().equals(after.limits())) changed.add("limits");
        if (!withoutEndAt(before.schedule()).equals(withoutEndAt(after.schedule()))) changed.add("schedule.startAt");
        if (!java.util.Objects.equals(before.exclusiveGroup(), after.exclusiveGroup())) changed.add("exclusiveGroup");
        if (before.visibleInPortal() != after.visibleInPortal()) changed.add("visibleInPortal");
        if (!before.labels().equals(after.labels())) changed.add("labels");
        return changed;
    }

    private static JsonNode withoutEndAt(JsonNode schedule) {
        if (schedule == null || !schedule.isObject()) {
            return schedule;
        }
        tools.jackson.databind.node.ObjectNode copy = ((tools.jackson.databind.node.ObjectNode) schedule).deepCopy();
        copy.remove("endAt");
        return copy;
    }

    private static boolean present(JsonNode n) {
        return n != null && !n.isNull();
    }

    /**
     * Transizione del ciclo di vita comune (docs/03 §3.6) con la policy (docs/06 §7): una campagna richiede LEGAL se
     * segnata {@code requiresLegal} o con budget ({@code limits.global.maxPoints}) oltre la soglia; le altre si
     * pubblicano direttamente. {@code ACTIVATE} resta un sinonimo di {@code PUBLISH}.
     */
    @Transactional
    public Campaign transition(String idOrCode, TransitionRequest req) {
        Campaign c = get(idOrCode);
        String id = c.id();
        String raw = req.action() == null ? "" : req.action().trim().toUpperCase();
        ApprovalAction a = GovernedTransitions.parse("ACTIVATE".equals(raw) ? "PUBLISH" : raw);
        if (a == ApprovalAction.ARCHIVE && c.system()) {
            throw LhException.conflict("SYSTEM_LOCKED", "Le campagne di sistema non si archiviano");
        }
        Role role = ActorHolder.get().role();
        ApprovalRule rule = ruleFor(c);
        ApprovalStatus from = ApprovalStatus.valueOf(c.status().name());
        ApprovalStatus next = GovernedTransitions.next(from, a, rule, policy.enabled(), role, req.comment());
        CampaignStatus to = CampaignStatus.valueOf(next.name());
        String actor = ActorHolder.get().asActorString();
        campaigns.updateStatus(id, to);
        history.record(ApprovalPolicy.CAMPAIGN, c.id(), from, next, a, actor, req.comment(), clock.instant());
        LhEvent<Map<String, Object>> fact = events.newRoot(
                LhEventTypes.Fact.CAMPAIGN_STATUS_CHANGED, "campaign:" + c.code(),
                Map.of("campaignCode", c.code(), "name", c.name(),
                        "previousStatus", from.name(), "newStatus", to.name()));
        outbox.write(fact); // fatto campaign.status.changed → lh.facts.v1 (chiave = memberId assente → subject)
        cache.reload();
        audit.record("CAMPAIGN", c.code(), AuditEntry.Action.TRANSITION,
                c.name() + ": " + from.name() + " → " + to.name() + " (" + a + ")"
                        + (GovernedTransitions.isOverride(a, rule, role) ? " [override ADMIN]" : "")
                        + (req.comment() == null || req.comment().isBlank() ? "" : " — " + req.comment().trim()),
                Map.of("status", from.name()), Map.of("status", to.name()));
        return campaigns.findById(id).orElseThrow();
    }

    /**
     * Fine automatica (docs/servizi/campaign-service.md §5): le campagne {@code LIVE}/{@code PAUSED} con
     * {@code schedule.endAt} superato ({@code asOf} oltre {@code endAt}, lo stesso confine del calendario del motore)
     * passano a {@code ENDED}: storico con azione {@code END} dell'attore {@code system}, fatto
     * {@code campaign.status.changed}, audit {@code JOB}, cache ricaricata. Idempotente: una campagna già
     * {@code ENDED} non è più candidata. Restituisce quante ne ha chiuse.
     */
    @Transactional
    public int endExpired(Instant asOf) {
        int ended = 0;
        for (Campaign c : campaigns.findAll()) {
            if (c.status() != CampaignStatus.LIVE && c.status() != CampaignStatus.PAUSED) {
                continue;
            }
            JsonNode end = c.schedule() == null ? null : c.schedule().get("endAt");
            if (end == null || end.isNull() || end.asString("").isBlank()
                    || !asOf.isAfter(Instant.parse(end.asString()))) {
                continue;
            }
            ApprovalStatus from = ApprovalStatus.valueOf(c.status().name());
            campaigns.updateStatus(c.id(), CampaignStatus.ENDED);
            history.record(ApprovalPolicy.CAMPAIGN, c.id(), from, ApprovalStatus.ENDED, ApprovalAction.END, "system",
                    "Fine calendario", clock.instant());
            outbox.write(events.newRoot(LhEventTypes.Fact.CAMPAIGN_STATUS_CHANGED, "campaign:" + c.code(),
                    Map.of("campaignCode", c.code(), "name", c.name(),
                            "previousStatus", from.name(), "newStatus", CampaignStatus.ENDED.name()),
                    LhSource.service("campaign"), "system"));
            audit.recordJob("CAMPAIGN", c.code(), c.name() + ": " + from.name() + " → ENDED (fine calendario)",
                    Map.of("status", from.name()), Map.of("status", CampaignStatus.ENDED.name()));
            ended++;
        }
        if (ended > 0) {
            cache.reload();
        }
        return ended;
    }

    /** Policy della campagna (docs/06 §7): {@code requiresLegal} o budget oltre soglia → LEGAL. */
    public ApprovalRule ruleFor(Campaign c) {
        JsonNode max = c.limits() == null ? null : c.limits().path("global").path("maxPoints");
        Long budget = max != null && max.isNumber() ? max.asLong() : null;
        return policy.forCampaign(c.requiresLegal(), budget);
    }

    /** Coda approvazioni nel formato comune (docs/06 §7): campagne in revisione o inviate da {@code submittedBy}. */
    @Override
    public List<ApprovalItem> approvals(String submittedBy) {
        List<Campaign> list = submittedBy == null || submittedBy.isBlank()
                ? campaigns.search(CampaignStatus.IN_REVIEW.name(), null, null)
                : history.submittedBy(ApprovalPolicy.CAMPAIGN, submittedBy).stream()
                        .map(campaigns::findById).flatMap(java.util.Optional::stream).toList();
        return list.stream().map(c -> ApprovalItem.of(ApprovalPolicy.CAMPAIGN, c.id(), c.code(), c.name(),
                c.status().name(), ruleFor(c), summary(c), history.list(ApprovalPolicy.CAMPAIGN, c.id()))).toList();
    }

    public List<ApprovalHistory> history(String idOrCode) {
        return history.list(ApprovalPolicy.CAMPAIGN, get(idOrCode).id());
    }

    /** Riepilogo per BO-21 (la frase generata la compone il backoffice dal dettaglio della campagna). */
    private String summary(Campaign c) {
        JsonNode max = c.limits() == null ? null : c.limits().path("global").path("maxPoints");
        String budget = max != null && max.isNumber() ? "budget " + max.asLong() + " punti" : "senza budget";
        return "Su " + String.join(", ", c.triggerActionTypes()) + " · " + budget + " · priorità " + c.priority()
                + (c.requiresLegal() ? " · richiede LEGAL" : "");
    }

    // ---------- validazione (docs §5) ----------

    public List<String> validate(CreateCampaignRequest r) {
        List<String> errors = new ArrayList<>();
        if (r.triggerActionTypes() == null || r.triggerActionTypes().isEmpty()) {
            errors.add("almeno un trigger");
        }
        JsonNode effects = r.effects();
        if (effects == null || !effects.isArray() || effects.isEmpty()) {
            errors.add("almeno un effetto");
        } else {
            for (JsonNode e : effects) {
                if (e.path("type").asString("").equals("MULTIPLIER")) {
                    double f = e.path("factor").asDouble(0);
                    if (f < 1.1 || f > 5) {
                        errors.add("MULTIPLIER.factor deve essere tra 1.1 e 5");
                    }
                }
                if (e.path("type").asString("").equals("GRANT_PLAYS")
                        && e.path("contestCode").asString("").isBlank()) {
                    errors.add("GRANT_PLAYS.contestCode obbligatorio");
                }
                // docs/servizi/campaign-service.md §5: codici premio e badge non vuoti.
                if (e.path("type").asString("").equals("ISSUE_COUPON")
                        && e.path("rewardCode").asString("").isBlank()
                        && e.path("rewardCodeField").asString("").isBlank()) {
                    errors.add("ISSUE_COUPON.rewardCode (o rewardCodeField) obbligatorio");
                }
                if (e.path("type").asString("").equals("AWARD_BADGE")
                        && e.path("badgeCode").asString("").isBlank()) {
                    errors.add("AWARD_BADGE.badgeCode obbligatorio");
                }
                if (e.path("type").asString("").equals("SEND_MESSAGE")) {
                    String templateCode = e.path("templateCode").asString("");
                    if (templateCode.isBlank()) {
                        errors.add("SEND_MESSAGE.templateCode obbligatorio");
                    } else if (!templateCode.matches("^[A-Z][A-Z0-9-]{2,39}$")) {
                        errors.add("SEND_MESSAGE.templateCode non valido");
                    }
                    if (e.has("params") && !e.get("params").isNull() && !e.get("params").isObject()) {
                        errors.add("SEND_MESSAGE.params deve essere un oggetto");
                    }
                }
            }
        }
        JsonNode schedule = r.schedule();
        if (schedule != null && schedule.has("startAt") && schedule.has("endAt")
                && !schedule.get("endAt").isNull() && !schedule.get("startAt").isNull()) {
            try {
                if (!Instant.parse(schedule.get("endAt").asString())
                        .isAfter(Instant.parse(schedule.get("startAt").asString()))) {
                    errors.add("endAt deve essere successivo a startAt");
                }
            } catch (Exception ignored) {
                errors.add("date di schedule non valide");
            }
        }
        return errors;
    }

    // ---------- portale ----------

    public List<PortalCampaignView> portal(String memberId, List<String> codes) {
        MemberSnapshot snapshot = memberId == null ? null : snapshots.findById(memberId).orElse(null);
        boolean byCode = codes != null && !codes.isEmpty();
        List<PortalCampaignView> out = new ArrayList<>();
        for (Campaign c : cache.live()) {
            boolean listed = byCode ? codes.contains(c.code()) : c.visibleInPortal();
            if (!listed || !audienceOk(c.audience(), snapshot)) {
                continue;
            }
            String endsAt = c.schedule() != null && c.schedule().hasNonNull("endAt")
                    ? c.schedule().get("endAt").asString() : null;
            out.add(new PortalCampaignView(c.code(), c.name(), c.memberDescription(), c.icon(),
                    rewardSummary(c.effects()), endsAt, memberLimit(c.limits())));
        }
        return out;
    }

    private static PortalCampaignView.MemberLimit memberLimit(JsonNode limits) {
        JsonNode perMember = limits == null ? null : limits.get("perMember");
        if (perMember == null || !perMember.isArray() || perMember.isEmpty()) {
            return null;
        }
        JsonNode first = perMember.get(0);
        return new PortalCampaignView.MemberLimit(first.path("max").asInt(0), first.path("period").asString(null));
    }

    private boolean audienceOk(JsonNode audience, MemberSnapshot member) {
        if (audience == null || audience.isEmpty() || audience.path("all").asBoolean(false)) {
            return true;
        }
        JsonNode tiers = audience.get("tiers");
        JsonNode segments = audience.get("segments");
        boolean hasTiers = tiers != null && tiers.isArray() && !tiers.isEmpty();
        boolean hasSegments = segments != null && segments.isArray() && !segments.isEmpty();
        if (!hasTiers && !hasSegments) {
            return true;
        }
        if (member == null) {
            return false;
        }
        if (hasTiers) {
            for (JsonNode t : tiers) {
                if (t.asString("").equals(member.tier())) {
                    return true;
                }
            }
        }
        if (hasSegments) {
            for (JsonNode s : segments) {
                if (member.segments().contains(s.asString(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String rewardSummary(JsonNode effects) {
        if (effects == null || !effects.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode e : effects) {
            switch (e.path("type").asString("")) {
                case "GRANT_POINTS" -> {
                    String mode = e.path("mode").asString("FIXED");
                    String unit = "STS".equals(e.path("currency").asString("PTS")) ? " punti status" : " punti";
                    if (mode.equals("FIXED")) {
                        parts.add("+" + e.path("value").asLong(0) + unit);
                    } else if (mode.equals("PER_AMOUNT")) {
                        parts.add(e.path("value").asLong(1) + " punto/i ogni " + e.path("unitStep").asLong(1) + " €");
                    }
                }
                case "MULTIPLIER" -> parts.add("Punti ×" + trimNumber(e.path("factor").asDouble(1)));
                case "GRANT_PLAYS" -> parts.add("+" + e.path("count").asLong(1) + " giocata");
                default -> {
                }
            }
        }
        return String.join(" · ", parts);
    }

    private static String trimNumber(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ---------- simulazione ----------

    public Evaluation simulate(SimulateRequest r) {
        if (r.action() == null || r.action().type() == null) {
            throw LhException.badRequest("action.type è obbligatorio");
        }
        String type = r.action().type();
        String shortType = type.startsWith(LhEventTypes.Action.PREFIX)
                ? type.substring(LhEventTypes.Action.PREFIX.length()) : type;
        Instant time = r.action().time() != null ? parse(r.action().time()) : clock.instant();
        EvalAction action = new EvalAction("sim-" + Ulid.next(clock), shortType, r.memberId(),
                canonicalSource(r.action().source()), time, r.action().data());

        MemberSnapshot base = r.memberId() == null ? null : snapshots.findById(r.memberId()).orElse(null);
        MemberSnapshot snapshot = applyOverride(base, r.memberId(), r.memberOverride());

        List<Campaign> pool;
        if (r.campaignIds() != null && !r.campaignIds().isEmpty()) {
            pool = cache.all().stream().filter(c -> r.campaignIds().contains(c.id())).toList();
        } else {
            pool = cache.live();
        }
        return engine.evaluate(action, snapshot, pool, counters);
    }

    /**
     * {@code context.source} è l'attributo {@code source} dell'azione, che nel motore reale è sempre l'URN
     * {@code urn:loyaltyhub:source:<codice>} (docs/05 §2, docs/03 §3.3). La simulazione accetta anche il solo codice
     * della fonte e lo porta nella stessa forma, così una condizione su {@code context.source} dà lo stesso esito.
     */
    static String canonicalSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String trimmed = source.trim();
        return trimmed.startsWith(LhSource.SOURCE_PREFIX) ? trimmed : LhSource.source(trimmed);
    }

    private MemberSnapshot applyOverride(MemberSnapshot base, String memberId, SimulateRequest.MemberOverride ov) {
        if (ov == null) {
            return base;
        }
        String id = memberId != null ? memberId : "SIM";
        String tier = ov.tier() != null ? ov.tier() : (base != null ? base.tier() : "BASE");
        List<String> segments = ov.segments() != null ? ov.segments()
                : (base != null ? base.segments() : List.of());
        JsonNode attributes = ov.attributes() != null ? ov.attributes()
                : (base != null ? base.attributes() : mapper.createObjectNode());
        return new MemberSnapshot(id, "ACTIVE", tier, segments,
                base != null ? base.labels() : List.of(), attributes,
                base != null ? base.registeredAt() : clock.instant(), base != null ? base.birthDate() : null);
    }

    private Instant parse(String time) {
        try {
            return Instant.parse(time);
        } catch (Exception e) {
            throw LhException.badRequest("action.time non è un istante valido: " + time);
        }
    }

    private JsonNode node(JsonNode value, String defaultJson) {
        return value == null || value.isNull() ? mapper.readTree(defaultJson) : value;
    }
}
