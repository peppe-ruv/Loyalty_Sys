package io.loyaltyhub.decisionservice.app;

import io.loyaltyhub.decisionservice.domain.DecisionContext;
import io.loyaltyhub.decisionservice.domain.PredictionProvider;
import io.loyaltyhub.decisionservice.domain.PredictionRouting;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provider esterno (RF-130, livello "External"): {@code POST url} con il contesto pseudonimo del membro (nessuna
 * anagrafica: solo id membro, tier, RFM, conteggi) e le chiavi richieste; risposta {@code {"predictions": {chiave: valore}}}.
 * Timeout breve e nessun retry: in caso di errore il composito ricade sulle regole. Serve anche per un modello locale
 * esposto come servizio (es. un container con un modello scikit-learn/ONNX).
 */
public final class HttpPredictionProvider implements PredictionProvider {
    /** Risposta del provider: {@code {"predictions": {chiave: valore}}}. */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };

    private final PredictionRouting.Provider cfg;
    private final RestClient client;

    public HttpPredictionProvider(PredictionRouting.Provider cfg) {
        this.cfg = cfg;
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(Math.max(100, cfg.timeoutMs())));
        f.setReadTimeout(Duration.ofMillis(Math.max(100, cfg.timeoutMs())));
        var b = RestClient.builder().baseUrl(cfg.url()).requestFactory(f);
        String key = cfg.apiKeyEnv() == null ? null : System.getenv(cfg.apiKeyEnv());
        if (key != null) b.defaultHeader("Authorization", "Bearer " + key);
        this.client = b.build();
    }

    @Override public String name() { return cfg.name(); }

    @Override
    public Map<String, Double> predict(DecisionContext ctx, Set<String> keys) {
        Map<String, Object> body = new HashMap<>();
        body.put("memberId", ctx.memberId());
        body.put("tier", ctx.tier());
        body.put("segments", ctx.segments());
        body.put("recencyDays", ctx.recencyDays());
        body.put("frequency90d", ctx.frequency90d());
        body.put("monetary365d", ctx.monetary365d());
        body.put("activity", ctx.activityOrNone());
        body.put("keys", keys);
        Map<String, Object> res = client.post().uri("").body(body).retrieve().body(JSON_OBJECT);
        Map<String, Double> out = new HashMap<>();
        Object p = res == null ? null : res.getOrDefault("predictions", res);
        if (p instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v instanceof Number n) out.put(k.toString(), PredictionProvider.clamp(n.doubleValue())); });
        return out;
    }
}
