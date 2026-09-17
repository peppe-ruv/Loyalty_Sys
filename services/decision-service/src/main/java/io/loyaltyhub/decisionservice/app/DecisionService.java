package io.loyaltyhub.decisionservice.app;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.metrics.LoyaltyMetrics;
import io.loyaltyhub.decisionservice.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrazione del ciclo decisionale (RF-127..RF-130, RF-134): evento → contesto (Customer 360) → previsioni →
 * eligibilità (campagne + offerte) → esperimento → motore → decision log → esecuzione effetti → evento DECISION_V1.
 * Espone anche Next Best Action e simulazione (nessun effetto, nessuna persistenza).
 */
@Service
public class DecisionService {
    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);
    private static final String SOURCE = "urn:loyaltyhub:decision-service";
    private static final Duration LOOKBACK = Duration.ofDays(31);

    private final DecisionEngine engine;
    private final Ports.PolicySource policies;
    private final Ports.OfferSource offers;
    private final Ports.ExperimentSource experiments;
    private final Ports.ContextSource contexts;
    private final Ports.CampaignEvaluations campaigns;
    private final Ports.OfferCondition offerCondition;
    private final PredictionProvider predictions;
    private final DecisionLog decisionLog;
    private final Effects effects;
    private final KafkaTemplate<String, byte[]> kafka;
    private final JdbcTemplate jdbc;
    private final LoyaltyMetrics metrics;

    public DecisionService(DecisionEngine engine, Ports.PolicySource policies, Ports.OfferSource offers, Ports.ExperimentSource experiments,
                           Ports.ContextSource contexts, Ports.CampaignEvaluations campaigns, Ports.OfferCondition offerCondition, PredictionProvider predictions,
                           DecisionLog decisionLog, Effects effects, KafkaTemplate<String, byte[]> kafka, JdbcTemplate jdbc, LoyaltyMetrics metrics) {
        this.engine = engine; this.policies = policies; this.offers = offers; this.experiments = experiments; this.contexts = contexts; this.campaigns = campaigns;
        this.offerCondition = offerCondition; this.predictions = predictions; this.decisionLog = decisionLog; this.effects = effects; this.kafka = kafka; this.jdbc = jdbc; this.metrics = metrics;
    }

    /** Ciclo completo per un'azione premiante ricevuta dal topic canonico. */
    public Decision decideForAction(String memberId, RewardingAction action, String eventId, String correlationId) {
        Instant now = Instant.now();
        DecisionContext ctx = enrich(contexts.load(memberId), now);
        var evaluation = campaigns.evaluate(memberId, action);
        List<Candidate> candidates = new ArrayList<>(Candidates.fromCampaigns(evaluation));
        Map<String, Object> event = eventVars(action);
        candidates.addAll(Candidates.fromOffers(offers.activeOffers(now), ctx, event, offerCondition, now));

        Assigned a = assign(ctx, action.actionType(), now);
        Decision d = engine.decide(eventId, action.actionType(), correlationId, ctx, candidates, a.policy(), a.experimentId(), a.variant(), now);
        decisionLog.save(d, true);
        metrics.decision(d.primaryAction(), a.experimentId() == null ? "-" : a.experimentId(), a.variant() == null ? "-" : a.variant());
        d.rejected().forEach(r -> metrics.decisionRejected(r.action().name(), r.reasonCode()));

        CanonicalEvents.withCorrelation(correlationId, () -> {
            execute(memberId, action.idempotencyKey(), d);
            publish(d);
            emitCampaignCompletion(memberId, action.idempotencyKey(), evaluation);
            emitChurnChange(memberId, ctx, action.idempotencyKey());
            if (a.newExposure()) effects.emitAction(memberId, EventTypes.ACTION_EXPERIMENT_EXPOSED, "exp:" + a.experimentId() + ":" + memberId, Map.of("experimentId", a.experimentId(), "variant", a.variant()));
        });
        return d;
    }

    /** Next Best Action (RF-129): {@code getNextBestAction(customerId, context)} — una sola azione, offerte del catalogo + contesto passato dal canale. */
    public Decision nextBestAction(String memberId, Map<String, Object> requestContext, boolean persist) {
        Instant now = Instant.now();
        DecisionContext ctx = enrich(contexts.load(memberId), now);
        Map<String, Object> event = new HashMap<>(requestContext == null ? Map.of() : requestContext);
        List<Candidate> candidates = Candidates.fromOffers(offers.activeOffers(now), ctx, event, offerCondition, now);
        Assigned a = assign(ctx, "next-best-action", now);
        Decision d = engine.nextBestAction(ctx, candidates, a.policy(), a.experimentId(), a.variant(), now);
        if (persist) {
            decisionLog.save(d, false);
            metrics.decision("NBA:" + d.primaryAction(), a.experimentId() == null ? "-" : a.experimentId(), a.variant() == null ? "-" : a.variant());
            publish(d);
        }
        return d;
    }

    /** Simulazione (RF-127): policy e offerte in bozza passate nel corpo, contesto reale o descritto a mano; nessun effetto. */
    public Decision simulate(String memberId, DecisionContext manualContext, RewardingAction action, DecisionPolicy draftPolicy, List<Offer> draftOffers, List<Candidate> extraCandidates) {
        Instant now = Instant.now();
        DecisionContext ctx = manualContext != null ? manualContext : enrich(contexts.load(memberId), now);
        if (ctx.predictions() == null || ctx.predictions().isEmpty()) ctx = ctx.withPredictions(predictions.predict(ctx, Set.of()));
        List<Candidate> candidates = new ArrayList<>();
        if (action != null) candidates.addAll(Candidates.fromCampaigns(campaigns.evaluate(ctx.memberId(), action)));
        List<Offer> all = new ArrayList<>(offers.activeOffers(now));
        if (draftOffers != null) all.addAll(draftOffers);
        candidates.addAll(Candidates.fromOffers(all, ctx, eventVars(action), offerCondition, now));
        if (extraCandidates != null) candidates.addAll(extraCandidates);
        DecisionPolicy policy = draftPolicy != null ? draftPolicy : policies.current();
        return engine.decide(action == null ? "simulation" : action.idempotencyKey(), action == null ? "simulation" : action.actionType(), null, ctx, candidates, policy, null, null, now);
    }

    // ---- passi -----------------------------------------------------------------------------------------------------

    private DecisionContext enrich(DecisionContext base, Instant now) {
        DecisionContext ctx = base == null ? DecisionContext.minimal("unknown", "BASE") : base;
        ctx = ctx.withPriorActions(decisionLog.priorActions(ctx.memberId(), now.minus(LOOKBACK)));
        try { ctx = ctx.withPredictions(predictions.predict(ctx, Set.of())); }
        catch (Exception e) { log.warn("predictions unavailable for member {}: {}", ctx.memberId(), e.toString()); }
        return ctx;
    }

    private record Assigned(DecisionPolicy policy, String experimentId, String variant, boolean newExposure) {}

    private Assigned assign(DecisionContext ctx, String eventType, Instant now) {
        DecisionPolicy base = policies.current();
        for (Experiment e : experiments.active(now)) {
            if (!e.appliesTo(eventType, ctx.segments(), now)) continue;
            Experiment.Variant v = e.assign(ctx.memberId());
            if (v == null) continue;
            DecisionPolicy p = base;
            if (v.policyId() != null) p = policies.byId(v.policyId()).orElse(base);
            p = v.control() ? p : Experiment.apply(p, v);
            boolean fresh = jdbc.update("INSERT INTO decisionservice.experiment_exposure(experiment_id, member_id, variant) VALUES (?,?,?) ON CONFLICT DO NOTHING", e.id(), ctx.memberId(), v.name()) > 0;
            if (fresh) metrics.experimentExposure(e.id(), v.name());
            return new Assigned(p, e.id(), v.name(), fresh);
        }
        return new Assigned(base, null, null, false);
    }

    /**
     * Chiave di idempotenza di un effetto: azione di origine, decisione, tipo di azione scelta.
     * Comincia sempre con la chiave dell'azione, così lo storno dell'azione ritrova gli effetti
     * per prefisso (RI-08), e distingue due effetti della stessa decisione — compresi due accrediti
     * sullo stesso wallet, che con la sola chiave dell'azione si sarebbero sovrascritti.
     */
    static String effectKey(String actionKey, String decisionId, Object action) {
        String shortId = decisionId.length() <= 8 ? decisionId : decisionId.substring(0, 8);
        return actionKey + ":" + shortId + ":" + action;
    }

    private void execute(String memberId, String actionKey, Decision d) {
        for (var c : d.actions()) {
            String key = effectKey(actionKey, d.decisionId(), c.action());
            try {
                switch (c.action()) {
                    // `key` e non `actionKey`: due campagne che premiano lo stesso wallet per lo stesso
                    // evento devono produrre due movimenti, non uno solo (il ledger è idempotente per
                    // chiave+wallet). Lo storno li ritrova comunque, per prefisso.
                    case AWARD_POINTS -> effects.awardUnits(memberId, key, c);
                    case ISSUE_REWARD -> effects.grantReward(memberId, c.reference(), key);
                    case ISSUE_COUPON -> effects.issueCoupon(memberId, c.reference(), key);
                    case GRANT_BADGE -> effects.grantBadge(memberId, c.reference(), key);
                    case SET_ATTRIBUTE -> effects.setAttribute(memberId, c.reference(), c.params() == null ? null : Objects.toString(c.params().get("value"), null));
                    case UPGRADE_TIER -> effects.assignTier(memberId, c.reference(), "decision:" + d.decisionId());
                    case EMIT_EVENT -> effects.emitAction(memberId, c.reference(), key, Map.of("decisionId", d.decisionId()));
                    case TRIGGER_CAMPAIGN -> effects.triggerCampaign(memberId, c.reference(), key);
                    case SEND_MESSAGE, SHOW_OFFER, ASK_FOR_FEEDBACK, NO_ACTION -> { /* consegnate dal delivery-service via DECISION_V1 */ }
                }
                metrics.decisionExecuted(c.action().name(), true);
            } catch (Exception e) {
                metrics.decisionExecuted(c.action().name(), false);
                log.error("effect {} failed for decision {}: {}", c.action(), d.decisionId(), e.toString());
            }
        }
    }

    /** Evento DECISION_V1: consumato da delivery-service (contatti), context-service (offerte recenti, previsioni) e BI. */
    private void publish(Decision d) {
        var primary = d.actions().isEmpty() ? null : d.actions().get(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", d.decisionId());
        payload.put("memberId", d.memberId());
        payload.put("eventId", d.eventId());
        payload.put("eventType", d.eventType());
        payload.put("policyVersion", d.policyVersion());
        payload.put("experimentId", d.experimentId());
        payload.put("variant", d.variant());
        payload.put("action", d.primaryAction());
        payload.put("reference", primary == null ? null : primary.reference());
        payload.put("channel", primary == null ? null : primary.channel());
        payload.put("reason", primary == null ? "NO_ACTION" : String.join("; ", primary.reasons()));
        payload.put("actions", d.actions());
        payload.put("rejected", d.rejected());
        payload.put("predictions", d.predictions());
        payload.put("riskLevel", d.riskLevel());
        payload.put("decidedAt", d.decidedAt().toString());
        payload.put("expiresAt", d.expiresAt().toString());
        var ce = CanonicalEvents.of(EventTypes.DECISION_V1, SOURCE, "member:" + d.memberId(), payload);
        kafka.send(EventTypes.TOPIC_DECISIONS, d.memberId(), CanonicalEvents.serialize(ce));
    }

    private void emitCampaignCompletion(String memberId, String actionKey, Ports.CampaignEvaluations.Result evaluation) {
        if (evaluation == null || evaluation.outcomes() == null) return;
        Set<String> done = new LinkedHashSet<>();
        evaluation.outcomes().forEach(o -> done.add(o.campaignId()));
        for (String campaignId : done) effects.emitAction(memberId, EventTypes.ACTION_CAMPAIGN_COMPLETED, actionKey + ":completed:" + campaignId, Map.of("campaignId", campaignId));
    }

    /** CHURN_RISK_CHANGED quando la previsione cambia fascia (bassa &lt; 0,33 ≤ media &lt; 0,66 ≤ alta) rispetto all'ultima nota nel Customer 360. */
    private void emitChurnChange(String memberId, DecisionContext ctx, String actionKey) {
        Double now = ctx.predictions() == null ? null : ctx.predictions().get("churnRisk");
        if (now == null) return;
        String band = band(now);
        String prev = jdbc.query("SELECT decision->'predictions'->>'churnRisk' FROM decisionservice.decision_log WHERE member_id = ? ORDER BY decided_at DESC LIMIT 1 OFFSET 1", (rs, i) -> rs.getString(1), memberId)
                .stream().filter(Objects::nonNull).findFirst().map(s -> band(Double.parseDouble(s))).orElse(null);
        if (prev != null && !prev.equals(band)) effects.emitAction(memberId, EventTypes.ACTION_CHURN_RISK_CHANGED, actionKey + ":churn:" + band, Map.of("from", prev, "to", band, "churnRisk", now));
    }

    private static String band(double v) { return v < 0.33 ? "LOW" : v < 0.66 ? "MEDIUM" : "HIGH"; }

    private static Map<String, Object> eventVars(RewardingAction a) {
        if (a == null) return Map.of();
        Map<String, Object> m = new HashMap<>(a.attributes() == null ? Map.of() : a.attributes());
        m.put("actionType", a.actionType());
        m.put("occurredAt", a.occurredAt());
        return m;
    }
}
