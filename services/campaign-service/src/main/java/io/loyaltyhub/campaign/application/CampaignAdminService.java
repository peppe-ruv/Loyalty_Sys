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
import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gestione delle campagne (docs/servizi/campaign-service.md §3): elenco, creazione, transizioni, validazione, portale, simulazione. */
@Service
public class CampaignAdminService {

    private final CampaignRepository campaigns;
    private final CounterRepository counters;
    private final MemberSnapshotRepository snapshots;
    private final CampaignCache cache;
    private final CampaignEngine engine;
    private final EvaluationService evaluation;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean approvalEnabled;

    public CampaignAdminService(CampaignRepository campaigns, CounterRepository counters,
                                MemberSnapshotRepository snapshots, CampaignCache cache, CampaignEngine engine,
                                EvaluationService evaluation, LhEventFactory events, OutboxWriter outbox,
                                ObjectMapper mapper, Clock clock,
                                @Value("${loyaltyhub.approval.enabled:false}") boolean approvalEnabled) {
        this.campaigns = campaigns;
        this.counters = counters;
        this.snapshots = snapshots;
        this.cache = cache;
        this.engine = engine;
        this.evaluation = evaluation;
        this.events = events;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
        this.approvalEnabled = approvalEnabled;
    }

    // ---------- letture ----------

    public List<CampaignSummary> list(String status, String actionType, String q) {
        return campaigns.search(status, actionType, q).stream()
                .map(c -> CampaignSummary.of(c, counters.totals(c.id()))).toList();
    }

    public Campaign get(String id) {
        return campaigns.findById(id).orElseThrow(() -> LhException.notFound("Campagna non trovata: " + id));
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
        if (campaigns.findByCode(r.code()).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Codice campagna già esistente: " + r.code());
        }
        Campaign c = new Campaign(
                Ulid.next(clock), r.code(), r.name(), r.description(), r.memberDescription(), r.icon(),
                r.triggerActionTypes() == null ? List.of() : r.triggerActionTypes(),
                node(r.audience(), "{\"all\":true}"), node(r.conditions(), "{\"op\":\"all\",\"rules\":[]}"),
                node(r.effects(), "[]"), node(r.limits(), "{}"), node(r.schedule(), "{}"),
                r.priority() == null ? 100 : r.priority(), r.exclusiveGroup(),
                r.visibleInPortal() != null && r.visibleInPortal(), false, false,
                r.labels() == null ? List.of() : r.labels(), CampaignStatus.DRAFT, 0, null, null);
        campaigns.insert(c);
        cache.reload();
        return c;
    }

    @Transactional
    public Campaign transition(String id, TransitionRequest req) {
        Campaign c = get(id);
        CampaignStatus from = c.status();
        CampaignStatus to = target(req.action(), from, c.system());
        if (to == from) {
            return c;
        }
        campaigns.updateStatus(id, to);
        LhEvent<Map<String, Object>> fact = events.newRoot(
                LhEventTypes.Fact.CAMPAIGN_STATUS_CHANGED, "campaign:" + c.code(),
                Map.of("campaignCode", c.code(), "name", c.name(),
                        "previousStatus", from.name(), "newStatus", to.name()));
        outbox.write(fact); // fatto campaign.status.changed → lh.facts.v1 (chiave = memberId assente → subject)
        cache.reload();
        return campaigns.findById(id).orElseThrow();
    }

    private CampaignStatus target(String action, CampaignStatus from, boolean system) {
        String a = action == null ? "" : action.trim().toUpperCase();
        return switch (a) {
            case "SUBMIT" -> approvalEnabled ? require(from, CampaignStatus.DRAFT, CampaignStatus.IN_REVIEW)
                    : require(from, CampaignStatus.LIVE, CampaignStatus.DRAFT, CampaignStatus.IN_REVIEW);
            case "PUBLISH", "ACTIVATE", "APPROVE" -> require(from, CampaignStatus.LIVE,
                    CampaignStatus.DRAFT, CampaignStatus.IN_REVIEW, CampaignStatus.PAUSED);
            case "PAUSE" -> require(from, CampaignStatus.PAUSED, CampaignStatus.LIVE);
            case "RESUME" -> require(from, CampaignStatus.LIVE, CampaignStatus.PAUSED);
            case "END" -> require(from, CampaignStatus.ENDED, CampaignStatus.LIVE, CampaignStatus.PAUSED);
            case "ARCHIVE" -> {
                if (system) {
                    throw LhException.conflict("SYSTEM_LOCKED", "Le campagne di sistema non si archiviano");
                }
                yield require(from, CampaignStatus.ARCHIVED,
                        CampaignStatus.DRAFT, CampaignStatus.ENDED, CampaignStatus.PAUSED, CampaignStatus.IN_REVIEW);
            }
            default -> throw LhException.badRequest("Transizione sconosciuta: " + action);
        };
    }

    private CampaignStatus require(CampaignStatus from, CampaignStatus to, CampaignStatus... allowedFrom) {
        for (CampaignStatus s : allowedFrom) {
            if (s == from) {
                return to;
            }
        }
        throw LhException.conflict("INVALID_TRANSITION", "Transizione non valida da " + from + " a " + to);
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

    public List<PortalCampaignView> portal(String memberId) {
        MemberSnapshot snapshot = memberId == null ? null : snapshots.findById(memberId).orElse(null);
        List<PortalCampaignView> out = new ArrayList<>();
        for (Campaign c : cache.live()) {
            if (!c.visibleInPortal() || !audienceOk(c.audience(), snapshot)) {
                continue;
            }
            String endsAt = c.schedule() != null && c.schedule().hasNonNull("endAt")
                    ? c.schedule().get("endAt").asString() : null;
            out.add(new PortalCampaignView(c.code(), c.name(), c.memberDescription(), c.icon(),
                    rewardSummary(c.effects()), endsAt));
        }
        return out;
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
                    if (mode.equals("FIXED")) {
                        parts.add("+" + e.path("value").asLong(0) + " punti");
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
                r.action().source(), time, r.action().data());

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
