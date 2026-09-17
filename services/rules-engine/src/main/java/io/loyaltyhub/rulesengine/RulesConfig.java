package io.loyaltyhub.rulesengine;

import io.loyaltyhub.rulesengine.client.LedgerClient;
import io.loyaltyhub.rulesengine.client.TierClient;
import io.loyaltyhub.rulesengine.domain.Rule;
import io.loyaltyhub.rulesengine.domain.RuleEvaluator;
import io.loyaltyhub.rulesengine.domain.RuleSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.rulesengine.client.RewardClient;
import io.loyaltyhub.rulesengine.client.SegmentClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adattatori di default. RuleSource in memoria = regole di esempio del seed; in produzione è sostituito
 * dall'adattatore verso il CMS (plugin loyalty) senza toccare il dominio.
 */
@Configuration
public class RulesConfig {

    /** Forme delle risposte JSON dei servizi interni: dichiararle evita i raw type di `List.class`/`Map.class`. */
    private static final ParameterizedTypeReference<List<String>> STRING_LIST = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Map<String, Number>>> WALLETS = new ParameterizedTypeReference<>() { };

    @Bean
    @ConditionalOnMissingBean
    RuleSource seedRuleSource() {
        List<Rule> seed = List.of(
                new Rule("self-reading", "1", "SELF_READING_SENT", List.of(), 50, 50, Map.of(), 0, null, null, true),
                new Rule("direct-debit", "1", "DIRECT_DEBIT_ACTIVATED", List.of(), 300, 300, Map.of(), 0, null, null, true),
                new Rule("bill-paid", "1", "BILL_PAID_ON_TIME", List.of(new Rule.Condition("amountEur", Rule.Operator.GT, "0")), 20, 20, Map.of(), 0, null, null, true),
                // RF-63 "general spending": 1 punto ogni euro sulle righe non di consegna, in sospeso 14 giorni (finestra di reso)
                new Rule("shop-spending", "1", "TRANSACTION", List.of(), 0, 0, Map.of("TOP", new java.math.BigDecimal("1.5")), 0, null, null, true,
                        new Rule.Earning(java.math.BigDecimal.ONE, "amountEur", java.math.BigDecimal.ONE,
                                new Rule.LineFilter(Set.of(), Set.of(), Set.of(), Set.of("delivery"), Set.of()), null),
                        Rule.Target.ALL, new Rule.Limits(0, 14, false)),
                // RF-68 referral: chi presenta riceve 500 punti al primo evento del presentato, max 10 volte l'anno
                new Rule("referral", "1", EventTypes.ACTION_REFERRAL_COMPLETED, List.of(), 500, 100, Map.of(), 0, null, null, true,
                        Rule.Earning.NONE, Rule.Target.ALL, new Rule.Limits(10, 0, false)),
                // RF-67 check-in entro 150 m dal negozio di Torino (coordinate di esempio), una volta al giorno
                new Rule("store-checkin", "1", EventTypes.ACTION_CHECK_IN, List.of(new Rule.Condition("lat", Rule.Operator.GEO_WITHIN, "45.0703,7.6869,150")), 30, 0, Map.of(), 0, null, null, true,
                        Rule.Earning.NONE, Rule.Target.ALL, new Rule.Limits(1, 0, false)),
                // RF-69 codice promozionale: i codici validi sono verificati da ingress-adapters; qui solo i punti
                new Rule("promo-code", "1", EventTypes.ACTION_CODE_REDEEMED, List.of(new Rule.Condition("campaign", Rule.Operator.EQ, "WELCOME2027")), 100, 0, Map.of(), 0, null, null, true)
        );
        return actionType -> seed.stream().filter(r -> r.actionType().equals(actionType)).toList();
    }

    @Bean
    @ConditionalOnMissingBean
    LedgerClient ledgerClient(RestClient.Builder builder) {
        RestClient client = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
        return new LedgerClient() {
            @Override public void post(String memberId, String actionKey, List<RuleEvaluator.Posting> postings) {
                // Il ledger è idempotente per (chiave, wallet): con la sola chiave dell'azione, due
                // campagne che premiano lo stesso wallet si scontrerebbero — una verrebbe persa e
                // l'inserimento dell'altra violerebbe il vincolo di unicità. Ogni campagna ha quindi
                // la sua chiave derivata, e gli effetti della stessa campagna sullo stesso wallet si
                // sommano in un movimento solo. Lo storno dell'azione li ritrova tutti, per prefisso.
                for (var p : merge(postings)) {
                    client.post().uri("/v1/ledger/postings").body(Map.of(
                            "memberId", memberId, "actionKey", postingKey(actionKey, p), "currency", p.currency().code(),
                            "amount", p.amount(), "reason", "RULE:" + p.ruleId() + "@" + p.ruleVersion(), "lockDays", p.lockDays(),
                            "expiresAt", p.expiresAt() == null ? "" : p.expiresAt().toString(), "pendingUntil", p.pendingUntil() == null ? "" : p.pendingUntil().toString())).retrieve().toBodilessEntity();
                }
            }
            @Override public void reverse(String originalActionKey) {
                client.post().uri("/v1/ledger/reversals/{k}", originalActionKey).retrieve().toBodilessEntity();
            }
            @Override public Map<String, io.loyaltyhub.rulesengine.campaign.CampaignEvaluator.WalletView> wallets(String memberId) {
                try {
                    Map<String, Map<String, Number>> body = client.get().uri("/v1/ledger/members/{id}/wallets", memberId).retrieve().body(WALLETS);
                    Map<String, io.loyaltyhub.rulesengine.campaign.CampaignEvaluator.WalletView> out = new java.util.HashMap<>();
                    if (body != null) body.forEach((k, v) -> out.put(k, new io.loyaltyhub.rulesengine.campaign.CampaignEvaluator.WalletView(
                            v.get("active").longValue(), v.get("earned").longValue(), v.get("spent").longValue(), v.get("pending").longValue(), v.get("blocked").longValue(), v.get("expired").longValue())));
                    return out;
                } catch (Exception e) { return Map.of(); }
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    io.loyaltyhub.rulesengine.campaign.CollectionSource collectionSource(RestClient.Builder builder) {
        RestClient client = builder.baseUrl(System.getenv().getOrDefault("SEGMENT_URL", "http://segment-service:8090")).build();
        return (collection, value) -> {
            try { return Boolean.TRUE.equals(client.get().uri("/v1/collections/{c}/contains?value={v}", collection, value).retrieve().body(Boolean.class)); }
            catch (Exception e) { return false; }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    io.loyaltyhub.rulesengine.campaign.ExpressionEngine expressionEngine(io.loyaltyhub.rulesengine.campaign.CollectionSource collections) {
        return new io.loyaltyhub.rulesengine.campaign.SpelExpressionEngine(collections);
    }

    @Bean
    @ConditionalOnMissingBean
    io.loyaltyhub.rulesengine.campaign.CampaignEvaluator campaignEvaluator(io.loyaltyhub.rulesengine.campaign.ExpressionEngine engine) {
        return new io.loyaltyhub.rulesengine.campaign.CampaignEvaluator(engine);
    }

    /** Campagne pubblicate: in memoria = regole seed convertite + una campagna con espressione; in produzione dal CMS. */
    @Bean
    @ConditionalOnMissingBean
    io.loyaltyhub.rulesengine.campaign.CampaignSource campaignSource(RuleSource rules) {
        var cashback = new io.loyaltyhub.rulesengine.campaign.Campaign("cashback-scaglioni", "1", "Cashback a scaglioni mensile", io.loyaltyhub.rulesengine.campaign.Campaign.Kind.DIRECT,
                new io.loyaltyhub.rulesengine.campaign.Campaign.Trigger(io.loyaltyhub.rulesengine.campaign.Campaign.Trigger.Type.PURCHASE_TRANSACTION, EventTypes.ACTION_TRANSACTION, null, null),
                null, null, true, io.loyaltyhub.rulesengine.campaign.Campaign.Visibility.EVERYONE,
                List.of(new io.loyaltyhub.rulesengine.campaign.Campaign.Rule("r1",
                        List.of(io.loyaltyhub.rulesengine.campaign.Campaign.Condition.expr("#transaction['grossValue'] > 0")),
                        List.of(io.loyaltyhub.rulesengine.campaign.Campaign.Effect.addUnitsExpr("PREMIO",
                                "#fn.round_down(#fn.percent_value_distribution(#transaction['grossValue'], {5000, 7500, 10000}, {0.01, 0.012, 0.015, 0}, #wallets['PREMIO'] == null ? 0 : #wallets['PREMIO'].earned))")))),
                new io.loyaltyhub.rulesengine.campaign.Campaign.Limits(0, io.loyaltyhub.rulesengine.campaign.Campaign.Limits.Period.NONE, 1_000_000, 0, io.loyaltyhub.rulesengine.campaign.Campaign.Limits.Period.NONE), 10, Map.of());
        return new io.loyaltyhub.rulesengine.campaign.CampaignSource() {
            @Override public List<io.loyaltyhub.rulesengine.campaign.Campaign> publishedCampaignsFor(String actionType) {
                var out = new java.util.ArrayList<>(rules.publishedRulesFor(actionType).stream().map(io.loyaltyhub.rulesengine.campaign.Campaign::fromRule).toList());
                if (EventTypes.ACTION_TRANSACTION.equals(actionType)) out.add(cashback);
                return out;
            }
            @Override public List<io.loyaltyhub.rulesengine.campaign.Campaign> scheduled() { return List.of(); }
        };
    }

    /**
     * Chiave di idempotenza dell'accredito di una campagna: comincia con la chiave dell'azione —
     * così lo storno dell'azione la ritrova per prefisso (RI-08) — e la distingue dalle altre
     * campagne premiate dallo stesso evento.
     */
    static String postingKey(String actionKey, RuleEvaluator.Posting posting) {
        return actionKey + ":" + posting.ruleId();
    }

    /**
     * Unisce gli accrediti della stessa campagna sullo stesso wallet in un movimento solo, sommando
     * le unità: la chiave del ledger è (azione:campagna, wallet) e due movimenti non ci starebbero.
     * Tiene la scadenza e la sospensione del primo effetto e l'ordine di arrivo.
     */
    static List<RuleEvaluator.Posting> merge(List<RuleEvaluator.Posting> postings) {
        var merged = new java.util.LinkedHashMap<String, RuleEvaluator.Posting>();
        for (var p : postings) {
            merged.merge(p.ruleId() + "|" + p.currency().code(), p,
                    (a, b) -> new RuleEvaluator.Posting(a.ruleId(), a.ruleVersion(), a.currency(), a.amount() + b.amount(),
                            a.lockDays(), a.autoRewardId(), a.expiresAt(), a.pendingUntil()));
        }
        return List.copyOf(merged.values());
    }

    @Bean
    @ConditionalOnMissingBean
    io.loyaltyhub.rulesengine.campaign.AutomationScheduler.AudienceSource audienceSource(RestClient.Builder builder) {
        RestClient rm = builder.baseUrl(System.getenv().getOrDefault("READ_MODEL_URL", "http://read-model:8088")).build();
        return c -> {
            try {
                List<String> body = rm.post().uri("/v1/read/audiences").body(Map.of("campaignId", c.id(), "visibility", c.visibility(), "schedule", c.customAttributes().getOrDefault("schedule", "DAILY"))).retrieve().body(STRING_LIST);
                return body == null ? java.util.stream.Stream.empty() : body.stream();
            } catch (Exception e) { return java.util.stream.Stream.empty(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    io.loyaltyhub.rulesengine.client.EngagementClient engagementClient(RestClient.Builder builder) {
        RestClient eng = builder.baseUrl(System.getenv().getOrDefault("ENGAGEMENT_URL", "http://engagement-service:8092")).build();
        RestClient members = builder.baseUrl(System.getenv().getOrDefault("MEMBER_URL", "http://member-service:8091")).build();
        RestClient ingress = builder.baseUrl(System.getenv().getOrDefault("INGRESS_URL", "http://ingress-adapters:8081")).build();
        return new io.loyaltyhub.rulesengine.client.EngagementClient() {
            @Override public Set<String> badgesOf(String memberId) {
                try {
                    List<String> badges = eng.get().uri("/v1/badges/members/{id}", memberId).retrieve().body(STRING_LIST);
                    return badges == null ? Set.of() : Set.copyOf(badges);
                } catch (Exception e) { return Set.of(); }
            }
            @Override public void grantBadge(String memberId, String badgeCode, String grantKey) {
                eng.post().uri("/v1/badges/{code}/grants", badgeCode).body(Map.of("memberId", memberId, "grantKey", grantKey)).retrieve().toBodilessEntity();
            }
            @Override public void setAttribute(String memberId, String key, String value) {
                members.patch().uri("/v1/members/{id}", memberId).body(Map.of("labels", java.util.Collections.singletonMap(key, value))).retrieve().toBodilessEntity();
            }
            @Override public void emit(String memberId, String actionType, String key) {
                ingress.post().uri("/v1/actions").body(Map.of("items", List.of(Map.of("memberId", memberId, "action", Map.of("actionType", actionType, "idempotencyKey", "campaign:" + key.replace(':', '-') + ":" + actionType, "occurredAt", java.time.Instant.now().toString(), "attributes", Map.of()))))).retrieve().toBodilessEntity();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    RewardClient rewardClient(RestClient.Builder builder) {
        RestClient client = builder.baseUrl(System.getenv().getOrDefault("CATALOG_URL", "http://catalog-redemption:8085")).build();
        return (memberId, rewardId, grantKey) -> client.post().uri("/v1/grants")
                .body(Map.of("memberId", memberId, "rewardId", rewardId, "grantKey", grantKey)).retrieve().toBodilessEntity();
    }

    @Bean
    @ConditionalOnMissingBean
    SegmentClient segmentClient(RestClient.Builder builder) {
        RestClient client = builder.baseUrl(System.getenv().getOrDefault("SEGMENT_URL", "http://segment-service:8090")).build();
        return memberId -> {
            try {
                List<String> body = client.get().uri("/v1/segments/members/{id}", memberId).retrieve().body(STRING_LIST);
                return body == null ? Set.of() : Set.copyOf(body);
            } catch (Exception e) { return Set.of(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    TierClient tierClient(RestClient.Builder builder) {
        RestClient client = builder.baseUrl(System.getenv().getOrDefault("TIER_URL", "http://tier-service:8084")).build();
        return new TierClient() {
            @Override public String currentTier(String memberId) {
                try {
                    Map<String, Object> body = client.get().uri("/v1/tiers/members/{id}", memberId).retrieve().body(JSON_OBJECT);
                    return body == null ? "BASE" : String.valueOf(body.getOrDefault("tier", "BASE"));
                } catch (Exception e) { return "BASE"; }
            }
            @Override public void assign(String memberId, String tierCode, String reason) {
                client.post().uri("/v1/tiers/members/{id}/assign", memberId).body(Map.of("tierCode", tierCode, "reason", reason)).retrieve().toBodilessEntity();
            }
        };
    }
}
