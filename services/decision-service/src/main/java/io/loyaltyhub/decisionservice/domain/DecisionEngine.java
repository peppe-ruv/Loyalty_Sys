package io.loyaltyhub.decisionservice.domain;

import io.loyaltyhub.decisionservice.domain.DecisionPolicy.ActionSpec;
import io.loyaltyhub.decisionservice.domain.DecisionPolicy.ActionType;
import io.loyaltyhub.decisionservice.domain.DecisionPolicy.Constraints;
import io.loyaltyhub.decisionservice.domain.DecisionPolicy.Scoring;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Motore decisionale (RF-127): trasforma i candidati ammissibili (effetti delle campagne, offerte del catalogo) in una
 * decisione spiegabile, applicando la policy configurata nel backoffice.
 *
 * <p>Le azioni "sempre applicate" ({@link DecisionPolicy#alwaysApply()}: punti, tier, badge, attributi, eventi) passano
 * senza arbitrato perché sono effetti contrattuali del programma — l'AI e la policy non modificano mai punti, saldi o
 * status (RF-130); le altre sono arbitrate: vincoli → punteggio → scelta delle prime {@code maxArbitratedPerEvent}.
 * Ogni scarto porta un codice motivo; ogni scelta porta canale, punteggio e motivazioni (RF-129).
 *
 * <p>Il motore è una funzione pura: nessun I/O, nessuna dipendenza da Spring; testabile e simulabile.
 */
public final class DecisionEngine {
    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");
    private static final List<String> RISK_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<ActionType> MESSAGING = Set.of(ActionType.SEND_MESSAGE, ActionType.ASK_FOR_FEEDBACK);
    private static final Set<ActionType> OFFERS = Set.of(ActionType.SHOW_OFFER, ActionType.ISSUE_COUPON, ActionType.ISSUE_REWARD, ActionType.TRIGGER_CAMPAIGN);
    private static final Set<ActionType> RETENTION = Set.of(ActionType.ISSUE_REWARD, ActionType.ISSUE_COUPON, ActionType.SHOW_OFFER, ActionType.SEND_MESSAGE);
    private static final Duration VALIDITY = Duration.ofHours(72);

    private final ScoreExpression expressions;

    public DecisionEngine(ScoreExpression expressions) { this.expressions = expressions; }

    /** Decisione per un evento: azioni contrattuali + arbitrato delle discrezionali. */
    public Decision decide(String eventId, String eventType, String correlationId, DecisionContext ctx, List<Candidate> candidates,
                           DecisionPolicy policy, String experimentId, String variant, Instant now) {
        List<Decision.Chosen> chosen = new ArrayList<>();
        List<Decision.Rejected> rejected = new ArrayList<>();
        List<Scored> arbitrable = new ArrayList<>();
        Set<ActionType> always = policy.alwaysApply() == null ? Set.of() : policy.alwaysApply();
        boolean blocked = riskAtLeast(ctx.riskLevel(), policy.constraints() == null ? null : policy.constraints().blockRiskLevel());

        for (Candidate c : candidates) {
            ActionSpec spec = policy.actions() == null ? null : policy.actions().get(c.type());
            if (spec == null || !spec.enabled()) { rejected.add(reject(c, "ACTION_DISABLED", "azione non abilitata nella policy " + policy.id())); continue; }
            if (riskAbove(ctx.riskLevel(), spec.maxRiskLevel())) { rejected.add(reject(c, "RISK_LEVEL", "rischio " + ctx.riskLevel() + " oltre " + spec.maxRiskLevel())); continue; }
            if (always.contains(c.type())) {
                chosen.add(new Decision.Chosen(c.type(), c.reference(), c.wallet(), c.units(), null, spec.priority(), List.of("ALWAYS_APPLY"), c.source(), c.sourceId(), c.params()));
                continue;
            }
            if (blocked) { rejected.add(reject(c, "RISK_BLOCK", "rischio " + ctx.riskLevel() + ": solo azioni contrattuali")); continue; }
            String violation = constraintViolation(c, spec, policy.constraints(), ctx, now);
            if (violation != null) { rejected.add(reject(c, violation.split(":")[0], violation)); continue; }
            String channel = channelFor(c, spec, policy, ctx);
            if (channel == null && needsChannel(c.type(), spec)) { rejected.add(reject(c, "NO_CHANNEL", "nessun canale disponibile entro i limiti di contatto")); continue; }
            List<String> reasons = new ArrayList<>();
            double score = score(c, spec, policy.scoring(), ctx, channel, reasons);
            arbitrable.add(new Scored(c, spec, channel, score, reasons));
        }

        arbitrable.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(s -> -s.spec().priority()));
        int k = Math.max(0, policy.maxArbitratedPerEvent());
        for (int i = 0; i < arbitrable.size(); i++) {
            Scored s = arbitrable.get(i);
            if (i < k) {
                chosen.add(new Decision.Chosen(s.c().type(), s.c().reference(), s.c().wallet(), s.c().units(), s.channel(), round(s.score()), s.reasons(), s.c().source(), s.c().sourceId(), s.c().params()));
            } else {
                rejected.add(reject(s.c(), "OUTRANKED", "punteggio " + round(s.score()) + " inferiore alle azioni scelte"));
            }
        }
        return new Decision(UUID.randomUUID().toString(), ctx.memberId(), eventId, eventType, correlationId, policy.id() + ":" + policy.version(),
                experimentId, variant, List.copyOf(chosen), List.copyOf(rejected), ctx.predictions() == null ? Map.of() : ctx.predictions(),
                ctx.riskLevel(), now, now.plus(VALIDITY));
    }

    /** Next Best Action (RF-129): una sola azione arbitrata, nessun effetto contrattuale. */
    public Decision nextBestAction(DecisionContext ctx, List<Candidate> candidates, DecisionPolicy policy, String experimentId, String variant, Instant now) {
        DecisionPolicy nba = new DecisionPolicy(policy.id(), policy.version(), policy.active(), policy.actions(), policy.constraints(), policy.scoring(),
                Set.of(), 1, policy.channelPreferenceOrder());
        List<Candidate> discretionary = candidates.stream().filter(c -> !DecisionPolicy.defaultAlwaysApply().contains(c.type())).toList();
        return decide("nba:" + UUID.randomUUID(), "next-best-action", null, ctx, discretionary, nba, experimentId, variant, now);
    }

    // ---- vincoli --------------------------------------------------------------------------------------------------

    private String constraintViolation(Candidate c, ActionSpec spec, Constraints k, DecisionContext ctx, Instant now) {
        if (spec.requiredConsents() != null) {
            for (String purpose : spec.requiredConsents()) if (!ctx.consented(purpose)) return "CONSENT_MISSING: manca il consenso '" + purpose + "'";
        }
        if (k != null) {
            if (k.suppressionSegments() != null && ctx.segments() != null) {
                for (String s : ctx.segments()) if (k.suppressionSegments().contains(s)) return "SUPPRESSED: membro nel segmento di esclusione '" + s + "'";
            }
            if (MESSAGING.contains(c.type()) && inQuietHours(k, now)) return "QUIET_HOURS: ore di silenzio " + k.quietHoursFrom() + "-" + k.quietHoursTo();
        }
        List<DecisionContext.PriorAction> same = ctx.priorActions().stream().filter(p -> p.action() == c.type()).toList();
        if (spec.cooldownHours() > 0) {
            Instant last = same.stream().map(DecisionContext.PriorAction::at).max(Comparator.naturalOrder()).orElse(null);
            if (last != null && Duration.between(last, now).toHours() < spec.cooldownHours()) return "COOLDOWN: ultima " + c.type() + " meno di " + spec.cooldownHours() + " ore fa";
        }
        if (spec.maxPerMemberPerPeriod() > 0) {
            Instant from = now.minus(periodDuration(spec.period()));
            long n = same.stream().filter(p -> !p.at().isBefore(from)).count();
            if (n >= spec.maxPerMemberPerPeriod()) return "PERIOD_LIMIT: " + n + "/" + spec.maxPerMemberPerPeriod() + " per " + spec.period();
        }
        if (k != null && OFFERS.contains(c.type()) && k.minHoursBetweenOffers() > 0) {
            Instant lastOffer = ctx.priorActions().stream().filter(p -> OFFERS.contains(p.action())).map(DecisionContext.PriorAction::at).max(Comparator.naturalOrder()).orElse(null);
            if (lastOffer != null && Duration.between(lastOffer, now).toHours() < k.minHoursBetweenOffers())
                return "OFFER_SPACING: ultima offerta meno di " + k.minHoursBetweenOffers() + " ore fa";
        }
        return null;
    }

    private static boolean inQuietHours(Constraints k, Instant now) {
        if (k.quietHoursFrom() == null || k.quietHoursTo() == null) return false;
        int h = ZonedDateTime.ofInstant(now, ZONE).getHour();
        int from = k.quietHoursFrom(), to = k.quietHoursTo();
        return from <= to ? (h >= from && h < to) : (h >= from || h < to);
    }

    private static Duration periodDuration(DecisionPolicy.Period p) {
        if (p == null) return Duration.ofDays(1);
        return switch (p) { case HOUR -> Duration.ofHours(1); case DAY -> Duration.ofDays(1); case WEEK -> Duration.ofDays(7); case MONTH -> Duration.ofDays(30); };
    }

    /** true se il livello di rischio del membro è ≥ soglia (usato per il blocco totale). */
    private static boolean riskAtLeast(String level, String threshold) {
        if (level == null || threshold == null) return false;
        int l = RISK_ORDER.indexOf(level.toUpperCase()), t = RISK_ORDER.indexOf(threshold.toUpperCase());
        return l >= 0 && t >= 0 && l >= t;
    }

    /** true se il livello di rischio del membro supera strettamente il massimo ammesso dall'azione. */
    private static boolean riskAbove(String level, String max) {
        if (level == null || max == null) return false;
        int l = RISK_ORDER.indexOf(level.toUpperCase()), m = RISK_ORDER.indexOf(max.toUpperCase());
        return l >= 0 && m >= 0 && l > m;
    }

    // ---- canale -----------------------------------------------------------------------------------------------------

    private static boolean needsChannel(ActionType t, ActionSpec spec) {
        return MESSAGING.contains(t) || t == ActionType.SHOW_OFFER || (spec.channels() != null && !spec.channels().isEmpty());
    }

    /** Canale: preferito dal candidato → preferito dal cliente → ordine della policy → canali dell'azione; sempre entro i cap di contatto. */
    static String channelFor(Candidate c, ActionSpec spec, DecisionPolicy policy, DecisionContext ctx) {
        Set<String> allowed = spec.channels() == null ? Set.of() : spec.channels();
        List<String> order = new ArrayList<>();
        if (c.preferredChannel() != null) order.add(c.preferredChannel());
        if (ctx.preferredChannel() != null) order.add(ctx.preferredChannel());
        if (policy.channelPreferenceOrder() != null) order.addAll(policy.channelPreferenceOrder());
        order.addAll(allowed);
        Map<String, Integer> caps = policy.constraints() == null || policy.constraints().contactCap7dByChannel() == null ? Map.of() : policy.constraints().contactCap7dByChannel();
        for (String ch : order) {
            if (!allowed.isEmpty() && !allowed.contains(ch)) continue;
            Integer cap = caps.get(ch);
            int used = ctx.contacts7dByChannel() == null ? 0 : ctx.contacts7dByChannel().getOrDefault(ch, 0);
            if (cap != null && used >= cap) continue;
            return ch;
        }
        return null;
    }

    // ---- punteggio --------------------------------------------------------------------------------------------------

    private double score(Candidate c, ActionSpec spec, Scoring scoring, DecisionContext ctx, String channel, List<String> reasons) {
        double value = c.value() != null ? c.value() : spec.baseValue();
        double cost = c.cost() != null ? c.cost() : spec.cost();
        double tierBoost = scoring == null || scoring.tierBoost() == null || ctx.tier() == null ? 1.0 : scoring.tierBoost().getOrDefault(ctx.tier(), 1.0);
        double propensity = ctx.prediction(propensityKey(c.type()), 0.5);
        double churn = ctx.prediction("churnRisk", 0.0);
        double recency = ctx.recencyDays() == null ? 0.5 : Math.min(1.0, ctx.recencyDays() / 90.0);
        Scoring.Strategy strategy = scoring == null || scoring.strategy() == null ? Scoring.Strategy.PRIORITY : scoring.strategy();
        reasons.add("source=" + c.source() + (c.sourceId() != null ? ":" + c.sourceId() : ""));
        if (channel != null) reasons.add("channel=" + channel);
        switch (strategy) {
            case PRIORITY -> { reasons.add("priority=" + spec.priority()); return spec.priority(); }
            case EXPRESSION -> {
                Map<String, Object> vars = new HashMap<>();
                vars.put("value", value); vars.put("cost", cost); vars.put("priority", spec.priority()); vars.put("tierBoost", tierBoost);
                vars.put("propensity", propensity); vars.put("churn", churn); vars.put("predictions", ctx.predictions() == null ? Map.of() : ctx.predictions());
                vars.put("tier", ctx.tier()); vars.put("channel", channel); vars.put("recencyDays", ctx.recencyDays());
                vars.put("frequency90d", ctx.frequency90d()); vars.put("monetary365d", ctx.monetary365d()); vars.put("action", c.type().name());
                double s = expressions == null ? spec.priority() : expressions.evaluate(scoring.expression(), vars);
                reasons.add("expression=" + round(s));
                return s;
            }
            default -> {
                double s = scoring.valueWeight() * value * tierBoost - scoring.costWeight() * cost + scoring.propensityWeight() * propensity * value;
                reasons.add("value=" + value + "×" + tierBoost + " cost=" + cost + " propensity=" + round(propensity));
                if (RETENTION.contains(c.type()) && churn > 0) { s += scoring.churnWeight() * churn * value; reasons.add("churnRisk=" + round(churn)); }
                if (scoring.recencyWeight() != 0) s += scoring.recencyWeight() * recency;
                if (channel != null && channel.equals(ctx.preferredChannel())) { s += scoring.channelPreferenceBonus(); reasons.add("preferredChannel"); }
                return s;
            }
        }
    }

    static String propensityKey(ActionType t) {
        return switch (t) {
            case ISSUE_REWARD, ISSUE_COUPON -> "rewardAcceptance";
            case SHOW_OFFER, TRIGGER_CAMPAIGN -> "offerPropensity";
            case SEND_MESSAGE, ASK_FOR_FEEDBACK -> "engagement";
            default -> "purchasePropensity";
        };
    }

    private static Decision.Rejected reject(Candidate c, String code, String detail) { return new Decision.Rejected(c.type(), c.reference(), code, detail); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private record Scored(Candidate c, ActionSpec spec, String channel, double score, List<String> reasons) {}
}
