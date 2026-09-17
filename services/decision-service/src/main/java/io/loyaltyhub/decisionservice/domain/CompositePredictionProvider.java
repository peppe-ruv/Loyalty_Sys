package io.loyaltyhub.decisionservice.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Instrada ogni chiave di previsione al provider configurato ({@link PredictionRouting}); su errore o timeout del
 * provider ricade sul provider a regole, così una previsione mancante non blocca mai una decisione (RF-130).
 */
public final class CompositePredictionProvider implements PredictionProvider {
    public static final Set<String> STANDARD_KEYS = Set.of("churnRisk", "purchasePropensity", "rewardAcceptance", "offerPropensity", "engagement", "customerValue");

    private final Function<PredictionRouting.Provider, PredictionProvider> factory;
    private final java.util.function.Supplier<PredictionRouting> routing;
    private final java.util.function.BiConsumer<String, Boolean> onCall;

    /**
     * @param factory costruisce un provider dalla sua configurazione (EXTERNAL → HTTP, LOCAL_ML → modello, RULE_BASED → regole)
     * @param onCall callback per le metriche: nome provider, esito
     */
    public CompositePredictionProvider(java.util.function.Supplier<PredictionRouting> routing, Function<PredictionRouting.Provider, PredictionProvider> factory,
                                       java.util.function.BiConsumer<String, Boolean> onCall) {
        this.routing = routing; this.factory = factory; this.onCall = onCall == null ? (a, b) -> {} : onCall;
    }

    @Override public String name() { return "composite"; }

    @Override
    public Map<String, Double> predict(DecisionContext ctx, Set<String> keys) {
        PredictionRouting r = routing.get();
        if (r == null || !r.enabled()) return Map.of();
        Set<String> wanted = keys == null || keys.isEmpty() ? STANDARD_KEYS : keys;
        Map<String, Set<String>> byProvider = new HashMap<>();
        for (String k : wanted) byProvider.computeIfAbsent(r.providerFor(k), x -> new HashSet<>()).add(k);
        Map<String, Double> out = new HashMap<>();
        PredictionProvider fallback = ruleBased(r);
        byProvider.forEach((name, ks) -> {
            PredictionRouting.Provider cfg = r.providers() == null ? null : r.providers().stream().filter(p -> p.name().equals(name) && p.active()).findFirst().orElse(null);
            Map<String, Double> res = null;
            if (cfg != null) {
                try { res = factory.apply(cfg).predict(ctx, ks); onCall.accept(name, true); }
                catch (Exception e) { onCall.accept(name, false); }
            }
            if (res == null) { res = fallback.predict(ctx, ks); if (cfg == null || !RuleBasedPredictionProvider.NAME.equals(name)) onCall.accept(RuleBasedPredictionProvider.NAME + ":fallback", true); }
            for (String k : ks) { Double v = res.get(k); if (v == null) v = fallback.predict(ctx, Set.of(k)).get(k); if (v != null) out.put(k, PredictionProvider.clamp(v)); }
            res.forEach((k, v) -> { if (k.startsWith("categoryAffinity:")) out.put(k, PredictionProvider.clamp(v)); });
        });
        return out;
    }

    private PredictionProvider ruleBased(PredictionRouting r) {
        List<PredictionRouting.Provider> ps = r.providers() == null ? List.of() : r.providers();
        return ps.stream().filter(p -> p.kind() == PredictionRouting.Kind.RULE_BASED).findFirst()
                .map(p -> new RuleBasedPredictionProvider(p.thresholds())).map(p -> (PredictionProvider) p).orElseGet(RuleBasedPredictionProvider::new);
    }
}
