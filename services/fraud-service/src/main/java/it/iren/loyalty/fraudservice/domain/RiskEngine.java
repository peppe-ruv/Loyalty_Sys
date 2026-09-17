package it.iren.loyalty.fraudservice.domain;

import it.iren.loyalty.fraudservice.domain.RiskPolicy.Signal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motore di rischio (RF-131): funzione pura dalle osservazioni aggregate del membro ({@link MemberActivity}) alla
 * valutazione (punteggio 0..100, livello, codici motivo con il valore osservato). Nessun I/O; simulabile dal backoffice.
 */
public final class RiskEngine {

    /** Osservazioni aggregate del membro su finestre temporali, calcolate dallo store degli eventi. */
    public record MemberActivity(String memberId, int redemptions24h, int accountsOnSameDevice30d, long unitsEarned24h, double avgDailyUnits30d,
                                 Long accountAgeHours, long unitsEarnedFirst24h, Double maxSpeedKmh, int failedCodeAttempts1h, int transactions30d,
                                 int returns30d, int distinctDevices7d, int events1h) {
        public static MemberActivity quiet(String memberId) { return new MemberActivity(memberId, 0, 1, 0, 0, null, 0, null, 0, 0, 0, 1, 0); }
    }

    public record Reason(String code, double observed, double intensity, double contribution, String description) {}

    public record Assessment(String memberId, int score, String level, List<String> reasonCodes, List<Reason> reasons, Instant assessedAt, String policyVersion) {
        public boolean blockedBy(RiskPolicy p) { return p.autoBlockLevel() != null && RiskEngine.order(level) >= RiskEngine.order(p.autoBlockLevel()); }
    }

    public Assessment assess(MemberActivity a, RiskPolicy p, Instant now) {
        Map<Signal, Double> observed = new LinkedHashMap<>();
        observed.put(Signal.REDEMPTION_FREQUENCY, (double) a.redemptions24h());
        observed.put(Signal.MULTI_ACCOUNT_DEVICE, (double) a.accountsOnSameDevice30d());
        observed.put(Signal.ABNORMAL_EARNING, a.avgDailyUnits30d() <= 0 ? (a.unitsEarned24h() > 0 ? 1.0 : 0.0) : a.unitsEarned24h() / a.avgDailyUnits30d());
        observed.put(Signal.RAPID_ACCOUNT_CREATION, a.accountAgeHours() != null && a.accountAgeHours() < 24 ? (double) a.unitsEarnedFirst24h() : 0.0);
        observed.put(Signal.IMPOSSIBLE_TRAVEL, a.maxSpeedKmh() == null ? 0.0 : a.maxSpeedKmh());
        observed.put(Signal.CODE_ABUSE, (double) a.failedCodeAttempts1h());
        observed.put(Signal.REFUND_RATIO, a.transactions30d() >= p.minTransactionsForRefundRatio() ? a.returns30d() / (double) a.transactions30d() : 0.0);
        observed.put(Signal.DEVICE_ANOMALY, (double) a.distinctDevices7d());
        observed.put(Signal.VELOCITY, (double) a.events1h());

        List<Reason> reasons = new ArrayList<>();
        double total = 0;
        for (var e : observed.entrySet()) {
            RiskPolicy.SignalSpec spec = p.signals() == null ? null : p.signals().get(e.getKey());
            if (spec == null || !spec.enabled()) continue;
            double intensity = spec.intensity(e.getValue());
            if (intensity <= 0) continue;
            double contribution = spec.weight() * intensity;
            total += contribution;
            reasons.add(new Reason(e.getKey().name(), round(e.getValue()), round(intensity), round(contribution), spec.description()));
        }
        reasons.sort(Comparator.comparingDouble(Reason::contribution).reversed());
        int score = (int) Math.round(Math.min(100, total));
        return new Assessment(a.memberId(), score, p.levelFor(score), reasons.stream().map(Reason::code).toList(), List.copyOf(reasons), now, p.id() + ":" + p.version());
    }

    /** Decadimento: senza nuovi segnali il punteggio si dimezza ogni {@code decayHours} (rivalutazione periodica). */
    public static int decayed(int score, Instant assessedAt, Instant now, RiskPolicy p) {
        if (p.decayHours() <= 0 || assessedAt == null) return score;
        double halfLives = Duration.between(assessedAt, now).toMinutes() / (60.0 * p.decayHours());
        return (int) Math.round(score * Math.pow(0.5, halfLives));
    }

    /** Distanza sferica (km) tra due coordinate, per il segnale di viaggio impossibile. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    public static double speedKmh(double lat1, double lon1, Instant t1, double lat2, double lon2, Instant t2) {
        double hours = Math.abs(Duration.between(t1, t2).toSeconds()) / 3600.0;
        double km = distanceKm(lat1, lon1, lat2, lon2);
        if (km < 1) return 0;
        return hours < 1.0 / 60 ? km * 60 : km / hours;
    }

    static int order(String level) { return switch (level == null ? "LOW" : level.toUpperCase()) { case "CRITICAL" -> 3; case "HIGH" -> 2; case "MEDIUM" -> 1; default -> 0; }; }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
