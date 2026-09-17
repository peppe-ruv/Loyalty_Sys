package io.loyaltyhub.decisionservice;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.metrics.LoyaltyMetrics;
import io.loyaltyhub.decisionservice.app.*;
import io.loyaltyhub.decisionservice.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;

/**
 * Cablaggio del decision-service: motore puro, SpEL, adattatori verso backoffice (CMS) e servizi (context, rules,
 * ledger, catalogo, engagement, member, tier, Kafka). Ogni porta è sostituibile nei test ({@code @ConditionalOnMissingBean}).
 */
@Configuration
public class DecisionConfig {

    /** Forma delle risposte JSON dei servizi interni (Customer 360, valutazioni delle campagne). */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };

    private static String env(String k, String def) { return System.getenv().getOrDefault(k, def); }

    @Bean @ConditionalOnMissingBean SpelSupport spel() { return new SpelSupport(); }
    @Bean @ConditionalOnMissingBean ScoreExpression scoreExpression(SpelSupport s) { return s; }
    @Bean @ConditionalOnMissingBean Ports.OfferCondition offerCondition(SpelSupport s) { return s; }
    @Bean @ConditionalOnMissingBean DecisionEngine decisionEngine(ScoreExpression s) { return new DecisionEngine(s); }

    /** Backoffice: policy, offerte, esperimenti, provider di previsione — con cache e fallback al seed. */
    @Bean @ConditionalOnMissingBean
    CmsSources cmsSources(RestClient.Builder builder) {
        RestClient cms = builder.baseUrl(env("CMS_URL", "http://cms:3000")).build();
        return new CmsSources(cms, Long.parseLong(env("CMS_CACHE_MS", "30000")), DecisionPolicy.example());
    }
    @Bean @ConditionalOnMissingBean Ports.PolicySource policySource(CmsSources c) { return c; }
    @Bean @ConditionalOnMissingBean Ports.OfferSource offerSource(CmsSources c) { return c; }
    @Bean @ConditionalOnMissingBean Ports.ExperimentSource experimentSource(CmsSources c) { return c; }
    @Bean @ConditionalOnMissingBean Ports.PredictionConfigSource predictionConfigSource(CmsSources c) { return c; }

    @Bean @ConditionalOnMissingBean
    PredictionProvider predictionProvider(Ports.PredictionConfigSource cfg, LoyaltyMetrics metrics) {
        return new CompositePredictionProvider(cfg::routing, p -> switch (p.kind()) {
            case RULE_BASED -> new RuleBasedPredictionProvider(p.thresholds());
            case LOCAL_ML, EXTERNAL -> new HttpPredictionProvider(p);
        }, metrics::prediction);
    }

    /** Customer 360 dal context-service (read-model): una sola chiamata per decisione (RF-126). */
    @Bean @ConditionalOnMissingBean
    Ports.ContextSource contextSource(RestClient.Builder builder) {
        RestClient rm = builder.baseUrl(env("READ_MODEL_URL", "http://read-model:8088")).build();
        return memberId -> {
            Map<String, Object> c;
            try { c = rm.get().uri("/v1/context/{id}", memberId).retrieve().body(JSON_OBJECT); }
            catch (Exception e) { c = null; }
            if (c == null) return DecisionContext.minimal(memberId, "BASE");
            Map<String, Object> loyalty = CmsSources.map(c.get("loyalty")), behaviour = CmsSources.map(c.get("behaviour")), engagement = CmsSources.map(c.get("engagement")), risk = CmsSources.map(c.get("risk")), identity = CmsSources.map(c.get("identity"));
            Map<String, Object> rfm = CmsSources.map(behaviour.get("rfm"));
            Map<String, Long> wallets = new HashMap<>();
            CmsSources.map(loyalty.get("wallets")).forEach((k, v) -> wallets.put(k, (long) CmsSources.num(CmsSources.map(v).get("active"), 0)));
            Map<String, Boolean> consents = new HashMap<>();
            CmsSources.map(c.get("consents")).forEach((k, v) -> consents.put(k, Boolean.TRUE.equals(v)));
            Map<String, Integer> contacts = new HashMap<>();
            CmsSources.map(engagement.get("contacts7dByChannel")).forEach((k, v) -> contacts.put(k, (int) CmsSources.num(v, 0)));
            Map<String, Integer> counts = new HashMap<>();
            CmsSources.map(behaviour.get("actionCounts30d")).forEach((k, v) -> counts.put(k, (int) CmsSources.num(v, 0)));
            List<Map<String, Object>> offers = CmsSources.rows(engagement.get("recentOffers"));
            int presented = (int) offers.stream().filter(o -> !"DECIDED".equals(o.get("outcome"))).count();
            int accepted = (int) offers.stream().filter(o -> "ACCEPTED".equals(o.get("outcome"))).count();
            Map<String, Double> predictions = new HashMap<>();
            CmsSources.map(c.get("predictions")).forEach((k, v) -> predictions.put(k, CmsSources.num(v, 0)));
            List<String> segments = loyalty.get("segments") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            int badges = engagement.get("badges") instanceof List<?> bl ? bl.size() : 0;
            var activity = new DecisionContext.Activity((int) CmsSources.num(engagement.get("redemptions90d"), 0), presented, accepted, counts, Map.of(),
                    CmsSources.instant(identity.get("enrolledAt")), badges);
            return new DecisionContext(memberId, CmsSources.str(loyalty.getOrDefault("tier", "BASE")), segments, wallets, consents, CmsSources.str(risk.get("level")),
                    CmsSources.intOrNull(rfm.get("recencyDays")), (int) CmsSources.num(rfm.get("frequency90d"), 0), CmsSources.num(rfm.get("monetary365d"), 0),
                    CmsSources.str(behaviour.get("preferredChannel")), contacts, List.of(), activity, predictions);
        };
    }

    /** Valutazione delle campagne senza effetti (rules-engine {@code POST /v1/evaluations}). */
    @Bean @ConditionalOnMissingBean
    Ports.CampaignEvaluations campaignEvaluations(RestClient.Builder builder) {
        RestClient rules = builder.baseUrl(env("RULES_URL", "http://rules-engine:8082")).build();
        return (memberId, action) -> {
            Map<String, Object> body = rules.post().uri("/v1/evaluations").body(Map.of("memberId", memberId, "action", action)).retrieve().body(JSON_OBJECT);
            if (body == null) return new Ports.CampaignEvaluations.Result(List.of(), List.of());
            List<Ports.CampaignEvaluations.Outcome> outcomes = new ArrayList<>();
            for (Map<String, Object> o : CmsSources.rows(body.get("outcomes"))) {
                Map<String, String> params = new HashMap<>();
                CmsSources.map(o.get("params")).forEach((k, v) -> params.put(k, v == null ? null : v.toString()));
                outcomes.add(new Ports.CampaignEvaluations.Outcome(CmsSources.str(o.get("campaignId")), CmsSources.str(o.get("version")), CmsSources.str(o.get("ruleId")), CmsSources.str(o.get("type")),
                        CmsSources.str(o.get("wallet")), (long) CmsSources.num(o.get("units"), 0), CmsSources.str(o.get("reference")), params, CmsSources.instant(o.get("expiresAt")), CmsSources.instant(o.get("pendingUntil"))));
            }
            List<Ports.CampaignEvaluations.Skipped> skipped = CmsSources.rows(body.get("skipped")).stream().map(s -> new Ports.CampaignEvaluations.Skipped(CmsSources.str(s.get("campaignId")), CmsSources.str(s.get("reason")))).toList();
            return new Ports.CampaignEvaluations.Result(outcomes, skipped);
        };
    }

    /** Esecuzione degli effetti sui servizi di dominio; le azioni interne vanno sul topic canonico (stessa forma delle esterne, ADR-008). */
    @Bean @ConditionalOnMissingBean
    Effects effects(RestClient.Builder builder, KafkaTemplate<String, byte[]> kafka) {
        RestClient ledger = builder.baseUrl(env("LEDGER_URL", "http://ledger:8083")).build();
        RestClient catalog = builder.baseUrl(env("CATALOG_URL", "http://catalog-redemption:8085")).build();
        RestClient engagement = builder.baseUrl(env("ENGAGEMENT_URL", "http://engagement-service:8092")).build();
        RestClient members = builder.baseUrl(env("MEMBER_URL", "http://member-service:8091")).build();
        RestClient tiers = builder.baseUrl(env("TIER_URL", "http://tier-service:8084")).build();
        String source = "urn:loyaltyhub:decision-service";
        return new Effects() {
            @Override public void awardUnits(String memberId, String actionKey, Decision.Chosen c) {
                Map<String, Object> p = c.params() == null ? Map.of() : c.params();
                ledger.post().uri("/v1/ledger/postings").body(Map.of(
                        "memberId", memberId, "actionKey", actionKey, "currency", c.wallet() == null ? "PREMIO" : c.wallet(), "amount", c.units(),
                        "reason", "DECISION:" + c.sourceId(), "lockDays", 0,
                        "expiresAt", Objects.toString(p.get("expiresAt"), ""), "pendingUntil", Objects.toString(p.get("pendingUntil"), ""))).retrieve().toBodilessEntity();
            }
            @Override public void grantReward(String memberId, String rewardId, String grantKey) {
                catalog.post().uri("/v1/grants").body(Map.of("memberId", memberId, "rewardId", rewardId, "grantKey", grantKey)).retrieve().toBodilessEntity();
            }
            @Override public void issueCoupon(String memberId, String rewardId, String grantKey) { grantReward(memberId, rewardId, grantKey); }
            @Override public void grantBadge(String memberId, String badgeCode, String grantKey) {
                engagement.post().uri("/v1/badges/{code}/grants", badgeCode).body(Map.of("memberId", memberId, "grantKey", grantKey)).retrieve().toBodilessEntity();
            }
            @Override public void setAttribute(String memberId, String key, String value) {
                members.patch().uri("/v1/members/{id}", memberId).body(Map.of("labels", Collections.singletonMap(key, value))).retrieve().toBodilessEntity();
            }
            @Override public void assignTier(String memberId, String tierCode, String reason) {
                tiers.post().uri("/v1/tiers/members/{id}/assign", memberId).body(Map.of("tierCode", tierCode, "reason", reason)).retrieve().toBodilessEntity();
            }
            @Override public void emitAction(String memberId, String actionType, String idempotencyKey, Map<String, Object> attributes) {
                var action = new RewardingAction(actionType, "decision:" + idempotencyKey.replace(':', '-') + ":" + actionType, null, Instant.now(), null, attributes);
                var ce = CanonicalEvents.action(source, memberId, action);
                kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(ce));
            }
        };
    }
}
