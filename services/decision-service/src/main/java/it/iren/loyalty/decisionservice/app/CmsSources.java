package it.iren.loyalty.decisionservice.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.iren.loyalty.decisionservice.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Adattatori verso il backoffice (Payload CMS, API REST): policy decisionali, offerte, esperimenti, provider di
 * previsione. Legge solo documenti {@code published}, con cache breve (RF-136: cambiare configurazione non richiede
 * rilascio) e fallback all'ultimo valore buono o al seed se il CMS non risponde: il backoffice giù non ferma le decisioni.
 *
 * <p>Le collezioni corrispondenti sono in {@code cms/src/collections.ts}: {@code decision-policies}, {@code offers},
 * {@code experiments}, {@code prediction-providers}.
 */
public final class CmsSources implements Ports.PolicySource, Ports.OfferSource, Ports.ExperimentSource, Ports.PredictionConfigSource {
    /** Forma della risposta del CMS: un oggetto JSON con `docs`, `totalDocs`, … */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };

    private static final Logger log = LoggerFactory.getLogger(CmsSources.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL).build();

    private final RestClient cms;
    private final long ttlMs;
    private final Map<String, Cached<?>> cache = new ConcurrentHashMap<>();
    private final DecisionPolicy seedPolicy;

    public CmsSources(RestClient cms, long ttlMs, DecisionPolicy seedPolicy) { this.cms = cms; this.ttlMs = ttlMs; this.seedPolicy = seedPolicy; }

    private record Cached<T>(T value, long at) {}

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> loader, T fallback) {
        Cached<T> c = (Cached<T>) cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < ttlMs) return c.value();
        try {
            T v = loader.get();
            cache.put(key, new Cached<>(v, now));
            return v;
        } catch (Exception e) {
            log.warn("cms {} unavailable ({}), using {}", key, e.toString(), c != null ? "last good value" : "seed");
            return c != null ? c.value() : fallback;
        }
    }

    @SuppressWarnings("unchecked") // Payload restituisce `docs` come lista di documenti JSON: il contenuto non è tipizzabile qui.
    private List<Map<String, Object>> docs(String collection, String where) {
        Map<String, Object> body = cms.get().uri("/api/{c}?where[status][equals]=published&limit=200&depth=0" + (where == null ? "" : "&" + where), collection).retrieve().body(JSON_OBJECT);
        Object docs = body == null ? null : body.get("docs");
        return docs instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    // ---- policy -----------------------------------------------------------------------------------------------------

    @Override
    public DecisionPolicy current() {
        List<DecisionPolicy> all = policies();
        return all.stream().filter(DecisionPolicy::active).findFirst().orElse(seedPolicy);
    }

    @Override
    public Optional<DecisionPolicy> byId(String id) { return policies().stream().filter(p -> p.id().equals(id)).findFirst(); }

    private List<DecisionPolicy> policies() {
        return cached("decision-policies", () -> docs("decision-policies", "sort=-isDefault").stream().map(CmsSources::toPolicy).toList(), List.of(seedPolicy));
    }

    static DecisionPolicy toPolicy(Map<String, Object> d) {
        Map<DecisionPolicy.ActionType, DecisionPolicy.ActionSpec> actions = new EnumMap<>(DecisionPolicy.ActionType.class);
        for (Map<String, Object> a : rows(d.get("actions"))) {
            DecisionPolicy.ActionType t = DecisionPolicy.ActionType.valueOf(str(a.get("type")));
            actions.put(t, new DecisionPolicy.ActionSpec(bool(a.get("enabled"), true), (int) num(a.get("priority"), 50), num(a.get("baseValue"), 1), num(a.get("cost"), 0),
                    strings(a.get("requiredConsents")), str(a.getOrDefault("maxRiskLevel", "MEDIUM")), (int) num(a.get("maxPerMemberPerPeriod"), 0),
                    DecisionPolicy.Period.valueOf(str(a.getOrDefault("period", "MONTH"))), (int) num(a.get("cooldownHours"), 0), strings(a.get("channels"))));
        }
        Map<String, Object> k = map(d.get("constraints"));
        Map<String, Integer> caps = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(k.get("contactCap7dByChannel"))) caps.put(str(r.get("channel")), (int) num(r.get("max"), 0));
        DecisionPolicy.Constraints constraints = new DecisionPolicy.Constraints(caps, intOrNull(k.get("quietHoursFrom")), intOrNull(k.get("quietHoursTo")),
                (long) num(k.get("dailyUnitsBudget"), 0), strings(k.get("suppressionSegments")), str(k.getOrDefault("blockRiskLevel", "CRITICAL")), (int) num(k.get("minHoursBetweenOffers"), 0));
        Map<String, Object> s = map(d.get("scoring"));
        Map<String, Double> tierBoost = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(s.get("tierBoost"))) tierBoost.put(str(r.get("tier")), num(r.get("boost"), 1));
        DecisionPolicy.Scoring scoring = new DecisionPolicy.Scoring(DecisionPolicy.Scoring.Strategy.valueOf(str(s.getOrDefault("strategy", "WEIGHTED"))),
                num(s.get("valueWeight"), 1), num(s.get("costWeight"), 1), num(s.get("propensityWeight"), 1), num(s.get("churnWeight"), 1), num(s.get("recencyWeight"), 0),
                tierBoost, num(s.get("channelPreferenceBonus"), 0), str(s.get("expression")));
        Set<DecisionPolicy.ActionType> always = new HashSet<>();
        for (String a : strings(d.get("alwaysApply"))) always.add(DecisionPolicy.ActionType.valueOf(a));
        if (always.isEmpty()) always = DecisionPolicy.defaultAlwaysApply();
        List<String> order = rows(d.get("channelPreferenceOrder")).stream().map(r -> str(r.get("channel"))).toList();
        return new DecisionPolicy(str(d.getOrDefault("code", d.get("id"))), String.valueOf(d.getOrDefault("version", 1)), bool(d.get("isDefault"), false),
                actions, constraints, scoring, always, (int) num(d.get("maxArbitratedPerEvent"), 1), order);
    }

    // ---- offerte ----------------------------------------------------------------------------------------------------

    @Override
    public List<Offer> activeOffers(Instant now) {
        return cached("offers", () -> docs("offers", null).stream().map(CmsSources::toOffer).toList(), List.<Offer>of()).stream().filter(o -> o.isActiveAt(now)).toList();
    }

    static Offer toOffer(Map<String, Object> d) {
        return new Offer(str(d.getOrDefault("code", d.get("id"))), str(d.get("name")), true, DecisionPolicy.ActionType.valueOf(str(d.get("action"))), str(d.get("reference")),
                str(d.get("wallet")), (long) num(d.get("units"), 0), str(d.get("condition")), num(d.get("value"), 1), num(d.get("cost"), 0), strings(d.get("channels")),
                instant(d.get("validFrom")), instant(d.get("validTo")), map(d.get("params")));
    }

    // ---- esperimenti ------------------------------------------------------------------------------------------------

    @Override
    public List<Experiment> active(Instant now) {
        return cached("experiments", () -> docs("experiments", null).stream().map(CmsSources::toExperiment).toList(), List.<Experiment>of())
                .stream().filter(e -> e.inWindow(now)).toList();
    }

    static Experiment toExperiment(Map<String, Object> d) {
        List<Experiment.Variant> variants = rows(d.get("variants")).stream().map(v -> new Experiment.Variant(str(v.get("name")), (int) num(v.get("weight"), 1), bool(v.get("control"), false),
                str(v.get("policy")), overrides(v.get("overrides")))).toList();
        return new Experiment(str(d.getOrDefault("code", d.get("id"))), str(d.get("name")), true, instant(d.get("startsAt")), instant(d.get("endsAt")), (int) num(d.get("trafficPercent"), 100),
                strings(d.get("eventTypes")), strings(d.get("segments")), variants, str(d.get("primaryMetric")));
    }

    private static Map<String, Object> overrides(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(o)) out.put(str(r.get("key")), r.get("value"));
        return out;
    }

    // ---- previsioni -------------------------------------------------------------------------------------------------

    @Override
    public PredictionRouting routing() {
        return cached("prediction-providers", () -> {
            List<Map<String, Object>> docs = docs("prediction-providers", null);
            if (docs.isEmpty()) return PredictionRouting.defaults();
            List<PredictionRouting.Provider> providers = new ArrayList<>();
            Map<String, String> byKey = new LinkedHashMap<>();
            String def = null;
            for (Map<String, Object> d : docs) {
                Map<String, Object> t = map(d.get("thresholds"));
                var thresholds = new RuleBasedPredictionProvider.Thresholds((int) num(t.get("churnRecencyDays"), 120), (int) num(t.get("activeFrequency90d"), 6),
                        num(t.get("highValueEur"), 1500), num(t.get("baseRewardAcceptance"), 0.35), num(t.get("baseOfferPropensity"), 0.3));
                String name = str(d.getOrDefault("code", d.get("id")));
                providers.add(new PredictionRouting.Provider(name, PredictionRouting.Kind.valueOf(str(d.getOrDefault("kind", "RULE_BASED"))), bool(d.get("active"), true),
                        str(d.get("url")), (int) num(d.get("timeoutMs"), 300), str(d.get("apiKeyEnv")), thresholds));
                for (String k : strings(d.get("servesKeys"))) byKey.put(k, name);
                if (bool(d.get("isDefault"), false)) def = name;
            }
            if (def == null) def = providers.stream().filter(p -> p.kind() == PredictionRouting.Kind.RULE_BASED).map(PredictionRouting.Provider::name).findFirst().orElse(RuleBasedPredictionProvider.NAME);
            return new PredictionRouting(providers, byKey, def, true);
        }, PredictionRouting.defaults());
    }

    // ---- helper -----------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(Object o) { return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of(); }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    public static Set<String> strings(Object o) {
        if (o instanceof List<?> l) {
            Set<String> out = new LinkedHashSet<>();
            for (Object x : l) { if (x instanceof Map<?, ?> m) { Object v = m.get("value"); if (v == null) v = m.get("channel"); if (v == null) v = m.get("purpose"); if (v != null) out.add(v.toString()); } else if (x != null) out.add(x.toString()); }
            return out;
        }
        return Set.of();
    }
    public static String str(Object o) { return o == null ? null : o.toString(); }
    public static double num(Object o, double fb) { return o instanceof Number n ? n.doubleValue() : o instanceof String s && !s.isBlank() ? Double.parseDouble(s) : fb; }
    public static Integer intOrNull(Object o) { return o instanceof Number n ? n.intValue() : null; }
    public static boolean bool(Object o, boolean fb) { return o instanceof Boolean b ? b : fb; }
    public static Instant instant(Object o) { return o == null ? null : Instant.parse(o.toString()); }
}
