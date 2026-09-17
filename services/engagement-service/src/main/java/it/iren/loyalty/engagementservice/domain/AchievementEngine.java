package it.iren.loyalty.engagementservice.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Applica un evento al progresso (RF-90): limiti di conteggio per periodo, obiettivo (overall / finestra / streak),
 * limiti di completamento. Pura, in orario Europe/Rome: giorno 00:00–23:59, settimana lun–dom, mese e anno di calendario.
 */
public class AchievementEngine {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    public record Outcome(AchievementProgress progress, boolean progressed, boolean completed, double currentPeriodValue, int consecutivePeriods) {}

    public Outcome apply(Achievement a, AchievementProgress p, String actionType, Map<String, Object> attrs, Instant at) {
        if (!a.active() || !a.matches(actionType, attrs)) return new Outcome(p, false, false, 0, 0);
        if (a.completionLimit() != null && a.completionLimit().max() > 0 && countIn(p, a.completionLimit().period(), at, true) >= a.completionLimit().max())
            return new Outcome(p, false, false, 0, 0);
        if (a.eventLimit() != null && a.eventLimit().max() > 0 && countIn(p, a.eventLimit().period(), at, false) >= a.eventLimit().max())
            return new Outcome(p, false, false, 0, 0);
        double value = a.valueOf(attrs);
        String unique = a.metric() == Achievement.Metric.UNIQUE_ATTRIBUTE_VALUES ? String.valueOf(attrs == null ? null : attrs.get(a.attribute())) : null;
        if (unique != null && p.events().stream().anyMatch(e -> unique.equals(e.unique()) && e.at().isAfter(cycleStart(p)))) return new Outcome(p, false, false, 0, 0);
        List<AchievementProgress.Counted> events = new ArrayList<>(p.events());
        events.add(new AchievementProgress.Counted(at, unique != null ? 1 : value, unique));
        AchievementProgress next = new AchievementProgress(p.memberId(), p.achievementId(), p.version(), events, p.completedCount(), p.lastCompletedAt());
        double current; int consecutive = 0; boolean done;
        Instant since = cycleStart(p);
        switch (a.goal().type()) {
            case OVERALL -> { current = sum(events, since, Instant.MAX); done = current >= a.goal().target(); }
            case LAST_DAYS -> { current = sum(events, maxOf(since, at.minus(Duration.ofDays(a.goal().windowDays()))), Instant.MAX); done = current >= a.goal().target(); }
            case CONSECUTIVE -> {
                consecutive = streak(events, since, a.goal(), at);
                current = sum(events, periodStart(at, a.goal().period()), Instant.MAX);
                done = consecutive >= a.goal().consecutivePeriods();
            }
            default -> throw new IllegalStateException();
        }
        if (done) next = new AchievementProgress(p.memberId(), p.achievementId(), p.version(), events, p.completedCount() + 1, at);
        return new Outcome(next, true, done, current, consecutive);
    }

    /** Dopo un completamento il conteggio riparte: gli eventi precedenti non contano per il ciclo successivo. */
    private static Instant cycleStart(AchievementProgress p) { return p.lastCompletedAt() == null ? Instant.MIN : p.lastCompletedAt(); }
    private static Instant maxOf(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    private static double sum(List<AchievementProgress.Counted> events, Instant from, Instant to) {
        return events.stream().filter(e -> e.at().isAfter(from) && e.at().isBefore(to)).mapToDouble(AchievementProgress.Counted::value).sum();
    }

    private static long countIn(AchievementProgress p, Achievement.Period period, Instant at, boolean completions) {
        if (period == null || period == Achievement.Period.TOTAL) return completions ? p.completedCount() : p.events().size();
        Instant start = periodStart(at, period);
        if (completions) return p.lastCompletedAt() != null && !p.lastCompletedAt().isBefore(start) ? 1 : 0; // un completamento per periodo al massimo, semplificazione documentata
        return p.events().stream().filter(e -> !e.at().isBefore(start)).count();
    }

    static Instant periodStart(Instant at, Achievement.Period period) {
        ZonedDateTime z = at.atZone(ROME);
        return switch (period) {
            case HOUR -> z.truncatedTo(ChronoUnit.HOURS).toInstant();
            case DAY -> z.toLocalDate().atStartOfDay(ROME).toInstant();
            case WEEK -> z.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay(ROME).toInstant();
            case MONTH -> z.toLocalDate().withDayOfMonth(1).atStartOfDay(ROME).toInstant();
            case YEAR -> z.toLocalDate().withDayOfYear(1).atStartOfDay(ROME).toInstant();
            case TOTAL -> Instant.MIN;
        };
    }

    /**
     * Chiave del periodo in ora di Roma: identifica il ciclo premiante di un achievement e,
     * con la stessa semantica, quello di una classifica (RF-100). Pubblica perché la usa anche
     * la chiusura dei cicli in {@code app.LeaderboardJobs}: le due devono coincidere, altrimenti
     * lo stesso ciclo verrebbe premiato due volte.
     */
    public static String periodKey(Instant at, Achievement.Period period) {
        ZonedDateTime z = at.atZone(ROME);
        return switch (period) {
            case HOUR -> z.truncatedTo(ChronoUnit.HOURS).toString();
            case DAY -> z.toLocalDate().toString();
            case WEEK -> z.get(IsoFields.WEEK_BASED_YEAR) + "-W" + z.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case MONTH -> z.getYear() + "-" + z.getMonthValue();
            case YEAR -> String.valueOf(z.getYear());
            case TOTAL -> "total";
        };
    }

    static Instant previousPeriod(Instant at, Achievement.Period period) {
        ZonedDateTime z = at.atZone(ROME);
        return switch (period) {
            case HOUR -> z.minusHours(1).toInstant(); case DAY -> z.minusDays(1).toInstant(); case WEEK -> z.minusWeeks(1).toInstant();
            case MONTH -> z.minusMonths(1).toInstant(); case YEAR -> z.minusYears(1).toInstant(); case TOTAL -> Instant.MIN;
        };
    }

    /** Streak: periodi consecutivi (incluso quello corrente) in cui la somma raggiunge la soglia per periodo. */
    static int streak(List<AchievementProgress.Counted> events, Instant since, Achievement.Goal g, Instant at) {
        Map<String, Double> byPeriod = new HashMap<>();
        for (var e : events) if (e.at().isAfter(since)) byPeriod.merge(periodKey(e.at(), g.period()), e.value(), Double::sum);
        int n = 0;
        Instant cursor = at;
        while (byPeriod.getOrDefault(periodKey(cursor, g.period()), 0d) >= g.target()) {
            n++;
            cursor = previousPeriod(cursor, g.period());
            if (n > 10_000) break;
        }
        return n;
    }
}
