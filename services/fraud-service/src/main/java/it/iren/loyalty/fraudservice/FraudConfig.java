package it.iren.loyalty.fraudservice;

import it.iren.loyalty.fraudservice.app.RiskService;
import it.iren.loyalty.fraudservice.domain.RiskPolicy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Cablaggio: policy dal backoffice (collezione {@code fraud-rules}, con cache e fallback al seed) e blocco sul ledger. */
@Configuration
public class FraudConfig {
    private static String env(String k, String def) { return System.getenv().getOrDefault(k, def); }

    @Bean @ConditionalOnMissingBean
    Supplier<RiskPolicy> riskPolicy(RestClient.Builder builder) {
        RestClient cms = builder.baseUrl(env("CMS_URL", "http://cms:3000")).build();
        long ttl = Long.parseLong(env("CMS_CACHE_MS", "30000"));
        var log = LoggerFactory.getLogger(FraudConfig.class);
        return new Supplier<>() {
            private volatile RiskPolicy last = RiskPolicy.example();
            private volatile long at = 0;
            @Override @SuppressWarnings("unchecked") public RiskPolicy get() {
                long now = System.currentTimeMillis();
                if (now - at < ttl) return last;
                try {
                    Map<String, Object> body = cms.get().uri("/api/fraud-rules?where[status][equals]=published&limit=1&depth=0").retrieve().body(Map.class);
                    List<Map<String, Object>> docs = body == null ? List.of() : (List<Map<String, Object>>) body.getOrDefault("docs", List.of());
                    if (!docs.isEmpty()) last = toPolicy(docs.get(0));
                } catch (Exception e) { log.warn("cms fraud-rules unavailable ({}), keeping {}", e.toString(), last.id()); }
                at = now;
                return last;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static RiskPolicy toPolicy(Map<String, Object> d) {
        Map<RiskPolicy.Signal, RiskPolicy.SignalSpec> signals = new EnumMap<>(RiskPolicy.Signal.class);
        RiskPolicy seed = RiskPolicy.example();
        for (Map<String, Object> s : (List<Map<String, Object>>) d.getOrDefault("signals", List.of())) {
            RiskPolicy.Signal sig = RiskPolicy.Signal.valueOf(String.valueOf(s.get("signal")));
            RiskPolicy.SignalSpec base = seed.signals().get(sig);
            signals.put(sig, new RiskPolicy.SignalSpec(!(s.get("enabled") instanceof Boolean b) || b, num(s.get("weight"), base.weight()), num(s.get("threshold"), base.threshold()),
                    num(s.get("saturation"), base.saturation()), s.get("description") == null ? base.description() : s.get("description").toString()));
        }
        if (signals.isEmpty()) signals = seed.signals();
        Object block = d.get("autoBlockLevel");
        return new RiskPolicy(String.valueOf(d.getOrDefault("code", d.get("id"))), String.valueOf(d.getOrDefault("version", 1)), signals,
                (int) num(d.get("mediumFrom"), 30), (int) num(d.get("highFrom"), 60), (int) num(d.get("criticalFrom"), 85),
                block == null || "NONE".equals(block) ? null : block.toString(), (int) num(d.get("decayHours"), 72), (int) num(d.get("minTransactionsForRefundRatio"), 5));
    }

    private static double num(Object o, double fb) { return o instanceof Number n ? n.doubleValue() : fb; }

    /** Blocco automatico: congela l'intero saldo attivo di ogni wallet (o lo sblocca); il ledger resta la sola fonte di verità. */
    @Bean @ConditionalOnMissingBean
    @SuppressWarnings("unchecked")
    RiskService.LedgerBlocker ledgerBlocker(RestClient.Builder builder) {
        RestClient ledger = builder.baseUrl(env("LEDGER_URL", "http://ledger:8083")).build();
        boolean enabled = Boolean.parseBoolean(env("FRAUD_AUTO_BLOCK", "true"));
        return (memberId, reason, unblock) -> {
            if (!enabled) return;
            Map<String, Map<String, Number>> wallets = ledger.get().uri("/v1/ledger/members/{id}/wallets", memberId).retrieve().body(Map.class);
            if (wallets == null) return;
            wallets.forEach((wallet, v) -> {
                long amount = v.get(unblock ? "blocked" : "active").longValue();
                if (amount <= 0) return;
                ledger.post().uri("/v1/ledger/blocks?unblock={u}", unblock).body(Map.of("memberId", memberId, "currency", wallet, "amount", amount,
                        "actionKey", "fraud:" + memberId + ":" + wallet + ":" + (unblock ? "unblock" : "block") + ":" + System.currentTimeMillis() / 60000, "reason", reason)).retrieve().toBodilessEntity();
            });
        };
    }
}
