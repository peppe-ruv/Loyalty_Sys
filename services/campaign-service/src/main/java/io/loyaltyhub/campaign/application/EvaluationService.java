package io.loyaltyhub.campaign.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.engine.CampaignEngine;
import io.loyaltyhub.campaign.engine.Counters;
import io.loyaltyhub.campaign.engine.EvalAction;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.GrantedEffect;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import io.loyaltyhub.campaign.engine.PeriodKeys;
import io.loyaltyhub.campaign.infra.CounterRepository;
import io.loyaltyhub.campaign.infra.EvaluationLogRepository;
import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrazione della valutazione (docs/03 §3.5, docs/servizi/campaign-service.md §5): carica lo snapshot,
 * chiama il motore puro, e nella <em>stessa transazione</em> scrive contatori, {@code evaluation_log} e
 * l'outbox (effetti {@code points.grant} + fatto {@code campaign.evaluated}). L'idempotenza sull'azione è
 * garantita a monte da {@code IdempotentHandler}; {@code evaluation_log} è comunque idempotente su {@code action_id}.
 */
@Service
public class EvaluationService {

    private final CampaignEngine engine;
    private final CampaignCache cache;
    private final CounterRepository counters;
    private final MemberSnapshotRepository snapshots;
    private final EvaluationLogRepository log;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public EvaluationService(CampaignEngine engine, CampaignCache cache, CounterRepository counters,
                             MemberSnapshotRepository snapshots, EvaluationLogRepository log,
                             LhEventFactory events, OutboxWriter outbox, ObjectMapper mapper) {
        this.engine = engine;
        this.cache = cache;
        this.counters = counters;
        this.snapshots = snapshots;
        this.log = log;
        this.events = events;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void evaluate(LhEvent<JsonNode> actionEvent) {
        EvalAction action = toAction(actionEvent);
        MemberSnapshot snapshot = action.memberId() == null ? null
                : snapshots.findById(action.memberId()).orElse(null);
        List<Campaign> live = cache.live();
        Evaluation ev = engine.evaluate(action, snapshot, live, counters);

        // Persistenza dei contatori solo per i match reali (la simulazione non passa di qui).
        if (ev.outcome() == Evaluation.Outcome.MATCHED) {
            Map<String, Campaign> byCode = live.stream().collect(Collectors.toMap(Campaign::code, Function.identity()));
            for (Evaluation.CampaignResult r : ev.results()) {
                if (!r.matched()) {
                    continue;
                }
                Campaign c = byCode.get(r.campaignCode());
                if (c == null) {
                    continue;
                }
                long points = ev.effects().stream()
                        .filter(g -> g.campaignCode().equals(c.code())).mapToLong(GrantedEffect::amount).sum();
                consumeLimits(c, action, points);
                counters.addTotals(c.id(), points, action.time());
            }
            counters.recordAction(action.memberId(), action.type(), action.time());
        } else if (ev.outcome() == Evaluation.Outcome.NO_MATCH && snapshot != null) {
            counters.recordAction(action.memberId(), action.type(), action.time());
        }

        log.save(action.actionId(), action.memberId(), action.type(), action.time(),
                actionEvent.lhcorrelationid(), ev.outcome().name(), mapper.writeValueAsString(ev.results()));

        // Outbox: effetti + fatto di spiegabilità (stessa transazione, propagando la correlazione).
        for (GrantedEffect g : ev.effects()) {
            outbox.write(events.childOf(actionEvent, LhEventTypes.Effect.POINTS_GRANT, effectData(action, g)));
        }
        outbox.write(events.childOf(actionEvent, LhEventTypes.Fact.CAMPAIGN_EVALUATED, evaluatedData(action, ev)));
    }

    /** Simulazione (docs §3): esegue il motore senza scrivere nulla; ritorna la spiegabilità. */
    public Evaluation simulate(EvalAction action, MemberSnapshot override, List<Campaign> campaigns) {
        MemberSnapshot snapshot = override != null ? override
                : (action.memberId() == null ? null : snapshots.findById(action.memberId()).orElse(null));
        return engine.evaluate(action, snapshot, campaigns, counters);
    }

    public List<Campaign> liveCampaigns() {
        return cache.live();
    }

    public List<Campaign> allCampaigns() {
        return cache.all();
    }

    // ---------- interni ----------

    private void consumeLimits(Campaign c, EvalAction action, long points) {
        JsonNode perMember = c.limits() == null ? null : c.limits().get("perMember");
        boolean any = false;
        if (perMember != null && perMember.isArray()) {
            for (JsonNode lim : perMember) {
                String period = lim.path("period").asString("ALWAYS");
                counters.addMemberMatch(c.id(), action.memberId(), period,
                        PeriodKeys.of(period, action.time()), points);
                any = true;
            }
        }
        if (!any) {
            // Nessun limite per membro: traccia comunque il match nel periodo ALWAYS (per statistiche).
            counters.addMemberMatch(c.id(), action.memberId(), "ALWAYS", "ALWAYS", points);
        }
    }

    private EvalAction toAction(LhEvent<JsonNode> e) {
        String type = e.type();
        String shortType = type.startsWith(LhEventTypes.Action.PREFIX)
                ? type.substring(LhEventTypes.Action.PREFIX.length()) : type;
        return new EvalAction(e.id(), shortType, e.memberId(), e.source(), e.time(), e.data());
    }

    private ObjectNode effectData(EvalAction action, GrantedEffect g) {
        ObjectNode d = mapper.createObjectNode();
        d.put("effectId", g.effectId());
        d.put("campaignCode", g.campaignCode());
        d.put("actionId", action.actionId());
        d.put("actionType", action.type());
        d.put("currency", g.currency());
        d.put("baseAmount", g.baseAmount());
        d.put("campaignMultiplier", g.campaignMultiplier());
        d.put("amount", g.amount());
        d.put("tierMultiplierApplies", g.tierMultiplierApplies());
        d.put("pendingDays", g.pendingDays());
        if (g.description() != null) {
            d.put("description", g.description());
        }
        return d;
    }

    private ObjectNode evaluatedData(EvalAction action, Evaluation ev) {
        ObjectNode d = mapper.createObjectNode();
        d.put("actionId", action.actionId());
        d.put("actionType", action.type());
        ArrayNode matched = d.putArray("matched");
        ArrayNode skipped = d.putArray("skipped");
        for (Evaluation.CampaignResult r : ev.results()) {
            if (r.matched()) {
                ObjectNode m = matched.addObject();
                m.put("campaignCode", r.campaignCode());
                ArrayNode effects = m.putArray("effects");
                for (Evaluation.EffectResult er : r.effects()) {
                    ObjectNode e = effects.addObject();
                    e.put("type", er.type());
                    if (er.currency() != null) {
                        e.put("currency", er.currency());
                    }
                    if (er.amount() != null) {
                        e.put("amount", er.amount());
                    }
                }
            } else {
                ObjectNode s = skipped.addObject();
                s.put("campaignCode", r.campaignCode());
                s.put("reason", r.reason() == null ? "" : r.reason().name());
            }
        }
        return d;
    }
}
