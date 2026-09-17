package io.loyaltyhub.decisionservice.domain;

import java.util.List;
import java.util.Map;

/**
 * Configurazione del livello previsionale (RF-130), dal backoffice (collezione {@code prediction-providers}):
 * i provider disponibili (a regole, modello locale, servizio esterno), quale provider serve ogni chiave di previsione
 * e il fallback. Cambiare provider non richiede rilascio: il motore continua a usare le stesse chiavi.
 */
public record PredictionRouting(List<Provider> providers, Map<String, String> providerByKey, String defaultProvider, boolean enabled) {
    public enum Kind { RULE_BASED, LOCAL_ML, EXTERNAL }
    /**
     * @param url endpoint POST del servizio (EXTERNAL) o path del modello (LOCAL_ML)
     * @param timeoutMs oltre il timeout si usa il fallback a regole: il provider non blocca mai la decisione
     * @param thresholds soglie del provider a regole
     */
    public record Provider(String name, Kind kind, boolean active, String url, int timeoutMs, String apiKeyEnv, RuleBasedPredictionProvider.Thresholds thresholds) {}

    public static PredictionRouting defaults() {
        return new PredictionRouting(List.of(new Provider(RuleBasedPredictionProvider.NAME, Kind.RULE_BASED, true, null, 0, null, RuleBasedPredictionProvider.Thresholds.DEFAULT)),
                Map.of(), RuleBasedPredictionProvider.NAME, true);
    }

    public String providerFor(String key) {
        String p = providerByKey == null ? null : providerByKey.get(key);
        return p == null ? defaultProvider : p;
    }
}
