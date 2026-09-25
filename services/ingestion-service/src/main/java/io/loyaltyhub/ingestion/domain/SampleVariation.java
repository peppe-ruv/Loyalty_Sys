package io.loyaltyhub.ingestion.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Piccole variazioni casuali del {@code sample_data} di un tipo per il simulatore (docs/servizi/ingestion-service.md §3
 * {@code POST /v1/demo/simulator/fire}: "{@code data} assente → {@code sample_data} del tipo con piccole variazioni
 * casuali"; F-DEMO-03, BO-28).
 * <ul>
 *   <li>si variano solo i numeri di primo livello (importi, letture, punteggi…): stringhe, enum, booleani, codici e
 *       oggetti/array annidati (es. {@code items[]}) restano quelli del campione;</li>
 *   <li>decimali: ±20 %, arrotondati a 2 cifre; interi: ±10 % (almeno ±1);</li>
 *   <li>il risultato resta nei limiti {@code minimum}/{@code maximum} (ed {@code exclusiveMinimum}/{@code exclusiveMaximum})
 *       dello schema del tipo: il simulatore non genera dati che la pipeline rifiuterebbe.</li>
 * </ul>
 * SPEC-GAP: Q-264 — «piccole» non è quantificato: scelte le ampiezze sopra.
 */
public final class SampleVariation {

    private static final double DECIMAL_SPREAD = 0.20;
    private static final double INTEGER_SPREAD = 0.10;

    private SampleVariation() {
    }

    /** Copia di {@code sample} con i numeri di primo livello variati; {@code schema} può essere {@code null}. */
    public static JsonNode vary(JsonNode sample, JsonNode schema, RandomGenerator random) {
        if (sample == null || !sample.isObject()) {
            return sample;
        }
        ObjectNode out = (ObjectNode) sample.deepCopy();
        JsonNode props = schema == null ? null : schema.get("properties");
        for (Map.Entry<String, JsonNode> e : sample.properties()) {
            JsonNode value = e.getValue();
            if (!value.isNumber()) {
                continue;
            }
            JsonNode fieldSchema = props == null ? null : props.get(e.getKey());
            if (value.isIntegralNumber()) {
                long base = value.asLong();
                long delta = Math.max(1L, Math.round(Math.abs(base) * INTEGER_SPREAD));
                long varied = base + random.nextLong(-delta, delta + 1);
                out.put(e.getKey(), clampLong(varied, base, fieldSchema));
            } else {
                double base = value.asDouble();
                double factor = 1 + random.nextDouble(-DECIMAL_SPREAD, DECIMAL_SPREAD);
                double varied = BigDecimal.valueOf(base * factor).setScale(2, RoundingMode.HALF_UP).doubleValue();
                out.put(e.getKey(), clampDouble(varied, base, fieldSchema));
            }
        }
        return out;
    }

    private static long clampLong(long v, long fallback, JsonNode s) {
        if (s == null) {
            return v;
        }
        if (s.has("minimum") && v < s.get("minimum").asDouble()) {
            v = (long) Math.ceil(s.get("minimum").asDouble());
        }
        if (s.has("exclusiveMinimum") && v <= s.get("exclusiveMinimum").asDouble()) {
            v = (long) Math.floor(s.get("exclusiveMinimum").asDouble()) + 1;
        }
        if (s.has("maximum") && v > s.get("maximum").asDouble()) {
            v = (long) Math.floor(s.get("maximum").asDouble());
        }
        if (s.has("exclusiveMaximum") && v >= s.get("exclusiveMaximum").asDouble()) {
            v = (long) Math.ceil(s.get("exclusiveMaximum").asDouble()) - 1;
        }
        return within(v, s) ? v : fallback;
    }

    private static double clampDouble(double v, double fallback, JsonNode s) {
        if (s == null) {
            return v;
        }
        if (s.has("minimum") && v < s.get("minimum").asDouble()) {
            v = s.get("minimum").asDouble();
        }
        if (s.has("maximum") && v > s.get("maximum").asDouble()) {
            v = s.get("maximum").asDouble();
        }
        return within(v, s) ? v : fallback;
    }

    private static boolean within(double v, JsonNode s) {
        return !(s.has("minimum") && v < s.get("minimum").asDouble())
                && !(s.has("maximum") && v > s.get("maximum").asDouble())
                && !(s.has("exclusiveMinimum") && v <= s.get("exclusiveMinimum").asDouble())
                && !(s.has("exclusiveMaximum") && v >= s.get("exclusiveMaximum").asDouble());
    }
}
