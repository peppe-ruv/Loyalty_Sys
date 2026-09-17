package it.iren.loyalty.fraudservice.domain;

import java.util.List;
import java.util.Map;

/**
 * Policy di rischio (RF-131), configurata nel backoffice (collezione {@code fraud-rules}): segnali con pesi e soglie,
 * fasce di livello, blocco automatico. Ogni segnale contribuisce al punteggio con {@code weight × intensità (0..1)};
 * il punteggio è 0..100. L'operatore può disattivare un segnale, cambiarne peso e soglie, o alzare la soglia di blocco
 * senza rilascio.
 *
 * @param mediumFrom/highFrom/criticalFrom soglie di punteggio delle fasce (sotto mediumFrom = LOW)
 * @param autoBlockLevel livello da cui bloccare automaticamente le unità del membro (null = mai)
 * @param decayHours dopo quante ore senza nuovi segnali il punteggio si dimezza (rivalutazione periodica)
 * @param minTransactionsForRefundRatio resi/transazioni valutato solo con almeno N transazioni
 */
public record RiskPolicy(String id, String version, Map<Signal, SignalSpec> signals, int mediumFrom, int highFrom, int criticalFrom,
                         String autoBlockLevel, int decayHours, int minTransactionsForRefundRatio) {

    public enum Signal {
        REDEMPTION_FREQUENCY, MULTI_ACCOUNT_DEVICE, ABNORMAL_EARNING, RAPID_ACCOUNT_CREATION, IMPOSSIBLE_TRAVEL,
        CODE_ABUSE, REFUND_RATIO, DEVICE_ANOMALY, VELOCITY
    }

    /**
     * @param weight contributo massimo al punteggio (0..100)
     * @param threshold valore osservato da cui il segnale inizia (intensità 0)
     * @param saturation valore osservato a cui il segnale è pieno (intensità 1); tra i due l'intensità è lineare
     */
    public record SignalSpec(boolean enabled, double weight, double threshold, double saturation, String description) {
        public double intensity(double observed) {
            if (!enabled || observed <= threshold) return 0;
            if (saturation <= threshold) return 1;
            return Math.min(1.0, (observed - threshold) / (saturation - threshold));
        }
    }

    public String levelFor(int score) {
        if (score >= criticalFrom) return "CRITICAL";
        if (score >= highFrom) return "HIGH";
        if (score >= mediumFrom) return "MEDIUM";
        return "LOW";
    }

    public static RiskPolicy example() {
        Map<Signal, SignalSpec> s = new java.util.EnumMap<>(Signal.class);
        s.put(Signal.REDEMPTION_FREQUENCY, new SignalSpec(true, 30, 3, 10, "riscatti nelle ultime 24 ore"));
        s.put(Signal.MULTI_ACCOUNT_DEVICE, new SignalSpec(true, 40, 2, 5, "account distinti visti sullo stesso dispositivo (30 giorni)"));
        s.put(Signal.ABNORMAL_EARNING, new SignalSpec(true, 35, 3, 10, "unità accumulate nelle 24 ore rispetto alla media giornaliera del membro (multiplo)"));
        s.put(Signal.RAPID_ACCOUNT_CREATION, new SignalSpec(true, 25, 500, 5000, "unità accumulate nelle prime 24 ore di vita dell'account"));
        s.put(Signal.IMPOSSIBLE_TRAVEL, new SignalSpec(true, 45, 150, 900, "velocità implicita tra due posizioni consecutive (km/h)"));
        s.put(Signal.CODE_ABUSE, new SignalSpec(true, 30, 5, 30, "tentativi falliti di codice nell'ultima ora"));
        s.put(Signal.REFUND_RATIO, new SignalSpec(true, 30, 0.3, 0.8, "quota di transazioni stornate/rese negli ultimi 30 giorni"));
        s.put(Signal.DEVICE_ANOMALY, new SignalSpec(true, 20, 3, 8, "dispositivi distinti usati dal membro in 7 giorni"));
        s.put(Signal.VELOCITY, new SignalSpec(true, 25, 20, 100, "eventi premianti nell'ultima ora"));
        return new RiskPolicy("default", "1", s, 30, 60, 85, "CRITICAL", 72, 5);
    }
}
