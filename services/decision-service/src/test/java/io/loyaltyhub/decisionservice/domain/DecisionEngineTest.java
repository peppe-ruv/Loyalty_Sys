package io.loyaltyhub.decisionservice.domain;

import io.loyaltyhub.decisionservice.domain.DecisionPolicy.ActionType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEngineTest {
    static final Instant NOON = Instant.parse("2027-03-10T11:00:00Z");
    static final Instant NIGHT = Instant.parse("2027-03-10T22:30:00Z");
    final DecisionPolicy policy = DecisionPolicy.example();
    final DecisionEngine engine = new DecisionEngine((e, v) -> ((Number) v.get("value")).doubleValue() * 2 - ((Number) v.get("cost")).doubleValue());
    final Map<String, Double> preds = Map.of("churnRisk", 0.7, "rewardAcceptance", 0.6, "offerPropensity", 0.2, "engagement", 0.5, "purchasePropensity", 0.4);
    final List<Candidate> cands = List.of(
            new Candidate(ActionType.AWARD_POINTS, "campaign", "c1@1", null, "PREMIO", 50, null, null, null, Map.of()),
            Candidate.of(ActionType.GRANT_BADGE, "campaign", "c2@1", "welcome"),
            Candidate.of(ActionType.ISSUE_REWARD, "campaign", "c3@1", "reward-10"),
            Candidate.of(ActionType.SHOW_OFFER, "offer", "o1", "offer-1"),
            Candidate.of(ActionType.SEND_MESSAGE, "offer", "o2", "msg-1"));

    static DecisionContext ctx(String tier, String risk, Map<String, Boolean> consents, List<String> segs, Map<String, Integer> contacts, List<DecisionContext.PriorAction> prior, Map<String, Double> preds) {
        return new DecisionContext("m1", tier, segs, Map.of("PREMIO", 1200L), consents, risk, 20, 4, 800.0, "app", contacts, prior, DecisionContext.Activity.NONE, preds);
    }

    @Test void contractualActionsAlwaysApplyAndOneDiscretionaryIsArbitrated() {
        var d = engine.decide("e1", "TRANSACTION", "corr", ctx("PLUS", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds), cands, policy, null, null, NOON);
        assertThat(d.actions()).extracting(Decision.Chosen::action).contains(ActionType.AWARD_POINTS, ActionType.GRANT_BADGE, ActionType.ISSUE_REWARD);
        assertThat(d.actions().stream().filter(c -> !DecisionPolicy.defaultAlwaysApply().contains(c.action())).count()).isEqualTo(1);
        assertThat(d.rejected()).filteredOn(r -> r.reasonCode().equals("OUTRANKED")).hasSize(2);
        assertThat(d.expiresAt()).isEqualTo(NOON.plus(Duration.ofHours(72)));
    }

    @Test void constraintsProduceReasonCodes() {
        var noConsent = engine.decide("e2", "TRANSACTION", null, ctx("BASE", "LOW", Map.of(), List.of(), Map.of(), List.of(), preds), cands, policy, null, null, NOON);
        assertThat(noConsent.rejected()).anyMatch(r -> r.action() == ActionType.SHOW_OFFER && r.reasonCode().equals("CONSENT_MISSING"));
        var quiet = engine.decide("e3", "CHECK_IN", null, ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds), cands.subList(3, 5), policy, null, null, NIGHT);
        assertThat(quiet.rejected()).anyMatch(r -> r.action() == ActionType.SEND_MESSAGE && r.reasonCode().equals("QUIET_HOURS"));
        assertThat(quiet.primaryAction()).isEqualTo("SHOW_OFFER");
        var suppressed = engine.decide("e4", "TRANSACTION", null, ctx("BASE", "LOW", Map.of("marketing", true), List.of("contenziosi"), Map.of(), List.of(), preds), cands, policy, null, null, NOON);
        assertThat(suppressed.rejected()).filteredOn(r -> r.reasonCode().equals("SUPPRESSED")).hasSize(3);
        var cooldown = engine.decide("e5", "TRANSACTION", null, ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(new DecisionContext.PriorAction(ActionType.ISSUE_REWARD, "r", NOON.minus(Duration.ofHours(5)))), preds), cands, policy, null, null, NOON);
        assertThat(cooldown.rejected()).anyMatch(r -> r.action() == ActionType.ISSUE_REWARD && r.reasonCode().equals("COOLDOWN"));
        assertThat(cooldown.rejected()).anyMatch(r -> r.action() == ActionType.SHOW_OFFER && r.reasonCode().equals("OFFER_SPACING"));
    }

    @Test void riskLimitsDiscretionaryActionsButNeverContractualOnes() {
        var high = engine.decide("e6", "TRANSACTION", null, ctx("BASE", "HIGH", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds), cands, policy, null, null, NOON);
        assertThat(high.rejected()).anyMatch(r -> r.action() == ActionType.ISSUE_REWARD && r.reasonCode().equals("RISK_LEVEL"));
        assertThat(high.actions()).anyMatch(c -> c.action() == ActionType.SHOW_OFFER);
        var critical = engine.decide("e7", "TRANSACTION", null, ctx("BASE", "CRITICAL", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds), cands, policy, null, null, NOON);
        assertThat(critical.actions()).isNotEmpty().allMatch(c -> DecisionPolicy.defaultAlwaysApply().contains(c.action()));
    }

    @Test void channelFallsBackWhenContactCapReached() {
        var msg = cands.subList(4, 5);
        var none = engine.decide("e8", "TRANSACTION", null, ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of("app", 7, "push", 3, "email", 2, "sms", 1), List.of(), preds), msg, policy, null, null, NOON);
        assertThat(none.rejected()).anyMatch(r -> r.reasonCode().equals("NO_CHANNEL"));
        var push = engine.decide("e9", "TRANSACTION", null, ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of("app", 7), List.of(), preds), msg, policy, null, null, NOON);
        assertThat(push.actions().get(0).channel()).isEqualTo("push");
    }

    @Test void strategiesAndNextBestAction() {
        var prio = new DecisionPolicy("p", "1", true, policy.actions(), policy.constraints(), new DecisionPolicy.Scoring(DecisionPolicy.Scoring.Strategy.PRIORITY, 0, 0, 0, 0, 0, Map.of(), 0, null), policy.alwaysApply(), 2, policy.channelPreferenceOrder());
        var d = engine.decide("e10", "TRANSACTION", null, ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds), cands, prio, null, null, NOON);
        assertThat(d.actions().stream().filter(c -> !DecisionPolicy.defaultAlwaysApply().contains(c.action())).map(Decision.Chosen::action)).containsExactly(ActionType.ISSUE_REWARD, ActionType.SHOW_OFFER);
        var expr = new DecisionPolicy("x", "1", true, policy.actions(), policy.constraints(), new DecisionPolicy.Scoring(DecisionPolicy.Scoring.Strategy.EXPRESSION, 0, 0, 0, 0, 0, Map.of(), 0, "#value*2-#cost"), policy.alwaysApply(), 1, policy.channelPreferenceOrder());
        var e = engine.decide("e11", "TRANSACTION", null, ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds), cands, expr, null, null, NOON);
        assertThat(e.actions()).filteredOn(c -> c.action() == ActionType.ISSUE_REWARD).first().extracting(Decision.Chosen::score).isEqualTo(15.0);
        var nba = engine.nextBestAction(ctx("TOP", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds), cands, policy, null, null, NOON);
        assertThat(nba.actions()).hasSize(1);
        assertThat(DecisionPolicy.defaultAlwaysApply()).doesNotContain(nba.actions().get(0).action());
    }

    @Test void ruleBasedPredictionsAndCompositeFallback() {
        var rb = new RuleBasedPredictionProvider();
        var active = new DecisionContext("m2", "PLUS", List.of(), Map.of(), Map.of(), null, 5, 8, 2000, "app", Map.of(), List.of(), new DecisionContext.Activity(2, 4, 2, Map.of("CHECK_IN", 6), Map.of("energia", 700.0, "shop", 300.0), null, 3), Map.of());
        var p = rb.predict(active, Set.of());
        assertThat(p.get("churnRisk")).isLessThan(0.05);
        assertThat(p.get("offerPropensity")).isEqualTo(0.5);
        assertThat(p.get("categoryAffinity:energia")).isEqualTo(0.7);
        assertThat(p.values()).allMatch(v -> v >= 0 && v <= 1);
        var routing = new PredictionRouting(List.of(new PredictionRouting.Provider("rule-based", PredictionRouting.Kind.RULE_BASED, true, null, 0, null, null),
                new PredictionRouting.Provider("ml", PredictionRouting.Kind.EXTERNAL, true, "http://x", 100, null, null)), Map.of("churnRisk", "ml"), "rule-based", true);
        var comp = new CompositePredictionProvider(() -> routing, cfg -> cfg.kind() == PredictionRouting.Kind.EXTERNAL ? new PredictionProvider() {
            public String name() { return "ml"; }
            public Map<String, Double> predict(DecisionContext c, Set<String> k) { throw new IllegalStateException("timeout"); }
        } : new RuleBasedPredictionProvider(cfg.thresholds()), null);
        assertThat(comp.predict(active, Set.of())).containsKeys("churnRisk", "purchasePropensity", "customerValue");
    }

    /**
     * Budget giornaliero di unità (RF-128): è il tetto economico del programma, non del membro. Finché c'era solo il
     * campo in configurazione, il motore non lo leggeva e una campagna sbagliata poteva svuotare la cassa in un giorno.
     */
    @Test void ilBudgetGiornalieroScartaLeAzioniCheNonCiStannoPiu() {
        var conBudget = conBudgetDi(1000);
        var premio = new Candidate(ActionType.ISSUE_REWARD, "campaign", "c1@1", "reward-300", "PREMIO", 300, null, null, null, Map.of());
        var contesto = ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds);

        var dentro = engine.decide("b1", "TRANSACTION", null, contesto, List.of(premio), conBudget, 700, null, null, NOON);
        assertThat(dentro.actions()).extracting(Decision.Chosen::action).containsExactly(ActionType.ISSUE_REWARD);

        var fuori = engine.decide("b2", "TRANSACTION", null, contesto, List.of(premio), conBudget, 701, null, null, NOON);
        assertThat(fuori.actions()).isEmpty();
        assertThat(fuori.rejected()).extracting(Decision.Rejected::reasonCode).containsExactly("UNITS_BUDGET");
        assertThat(fuori.rejected().get(0).detail()).contains("701 già concesse");
    }

    /** Il tetto non spegne la giornata: un'azione più piccola che ci sta ancora passa, e quelle senza unità sempre. */
    @Test void ilBudgetEsauritoNonBloccaLeAzioniSenzaUnita() {
        var conBudget = conBudgetDi(100);
        var grande = new Candidate(ActionType.ISSUE_REWARD, "campaign", "c1@1", "reward-90", "PREMIO", 90, 100.0, null, null, Map.of());
        var messaggio = Candidate.of(ActionType.SEND_MESSAGE, "offer", "o2", "msg-1");
        var contesto = ctx("BASE", "LOW", Map.of("marketing", true), List.of(), Map.of(), List.of(), preds);

        var d = engine.decide("b3", "TRANSACTION", null, contesto, List.of(grande, messaggio), conBudget, 50, null, null, NOON);

        assertThat(d.actions()).extracting(Decision.Chosen::action).containsExactly(ActionType.SEND_MESSAGE);
        assertThat(d.rejected()).extracting(Decision.Rejected::reasonCode).containsExactly("UNITS_BUDGET");
    }

    /** Le azioni contrattuali sono effetti dovuti del programma: il budget non le tocca mai (RF-130). */
    @Test void ilBudgetNonToccaLeAzioniContrattuali() {
        var conBudget = conBudgetDi(10);
        var punti = new Candidate(ActionType.AWARD_POINTS, "campaign", "c1@1", null, "PREMIO", 5000, null, null, null, Map.of());
        var d = engine.decide("b4", "TRANSACTION", null, ctx("BASE", "LOW", Map.of(), List.of(), Map.of(), List.of(), preds),
                List.of(punti), conBudget, 999_999, null, null, NOON);
        assertThat(d.actions()).extracting(Decision.Chosen::action).containsExactly(ActionType.AWARD_POINTS);
    }

    private DecisionPolicy conBudgetDi(long budget) {
        var k = policy.constraints();
        return new DecisionPolicy(policy.id(), policy.version(), policy.active(), policy.actions(),
                new DecisionPolicy.Constraints(k.contactCap7dByChannel(), null, null, budget, k.suppressionSegments(), k.blockRiskLevel(), 0),
                policy.scoring(), policy.alwaysApply(), 2, policy.channelPreferenceOrder());
    }

    @Test void experimentsAreDeterministicAndOverrideOnlyScoring() {
        var exp = new Experiment("exp1", "x", true, null, null, 100, Set.of("TRANSACTION"), Set.of(),
                List.of(new Experiment.Variant("control", 50, true, null, Map.of()), new Experiment.Variant("v", 50, false, null, Map.of("scoring.propensityWeight", 5, "maxArbitratedPerEvent", "2"))), "conversion");
        assertThat(exp.assign("member-A").name()).isEqualTo(exp.assign("member-A").name());
        long control = java.util.stream.IntStream.range(0, 2000).filter(i -> exp.assign("m" + i).control()).count();
        assertThat(control).isBetween(850L, 1150L);
        assertThat(exp.appliesTo("CHECK_IN", List.of(), NOON)).isFalse();
        var over = Experiment.apply(policy, exp.variants().get(1));
        assertThat(over.scoring().propensityWeight()).isEqualTo(5.0);
        assertThat(over.maxArbitratedPerEvent()).isEqualTo(2);
        assertThat(over.alwaysApply()).isEqualTo(policy.alwaysApply());
    }
}
