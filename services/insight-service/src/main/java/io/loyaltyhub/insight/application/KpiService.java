package io.loyaltyhub.insight.application;

import io.loyaltyhub.common.time.BusinessCalendar;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.MetricRepository;
import io.loyaltyhub.insight.infra.MetricRepository.Point;
import io.loyaltyhub.insight.infra.MetricRepository.Slice;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * KPI di programma per BO-01 (docs/servizi/insight-service.md §3, docs/08 §BO-01). Legge {@code metric_daily}
 * (dati reali + storico sintetico) e l'event store. «Oggi» è il giorno di business in {@code Europe/Rome} (docs/03).
 * Parametri errati (metrica o granularità sconosciute, finestra vuota o rovesciata, limiti &lt; 1) ⇒ 400 (docs/06 §2).
 */
@Service
public class KpiService {

    /** Membri caricati dal seed (docs/10 §2): base per il totale, i nuovi arrivano dagli eventi. */
    private static final long SEED_MEMBERS = 12;

    /** Metriche di insight §2 (più il totale membri sintetico di docs/10 §9): le sole interrogabili. */
    public static final Set<String> METRICS = Set.of(
            "actions", "points_earned", "points_spent", "points_expired", "points_by_campaign", "members_new",
            "members_active", "members_total", "redemptions", "plays", "wins", "tier_changes", "messages", "dlq");

    private final MetricRepository metrics;
    private final EventStoreRepository events;
    private final Clock clock;

    public KpiService(MetricRepository metrics, EventStoreRepository events, Clock clock) {
        this.metrics = metrics;
        this.events = events;
        this.clock = clock;
    }

    /** Oggi nel fuso di business del programma (docs/03: {@code Europe/Rome}), non in UTC. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(BusinessCalendar.ZONE));
    }

    /**
     * Normalizza la finestra: se {@code from/to} assenti usa gli ultimi {@code days} giorni fino a oggi.
     * {@code days} &lt; 1 o {@code from} dopo {@code to} ⇒ 400.
     */
    // SPEC-GAP: Q-323 — days < 1 e from > to non sono corretti in silenzio (finestra di un giorno o a zero): 400.
    public Window window(LocalDate from, LocalDate to, int days) {
        if (days < 1) {
            throw LhException.badRequest("Parametro days non valido (atteso ≥ 1): " + days);
        }
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusDays(days - 1L);
        if (start.isAfter(end)) {
            throw LhException.badRequest("Finestra non valida: from " + start + " dopo to " + end);
        }
        return new Window(start, end);
    }

    /** Metrica di insight §2; sconosciuta ⇒ 400 (Q-326). */
    // SPEC-GAP: Q-326 — una metrica sconosciuta non restituisce una serie vuota (sembrerebbe «zero»): 400.
    public static String metric(String metric) {
        if (metric == null || !METRICS.contains(metric)) {
            throw LhException.badRequest("Metrica sconosciuta: " + metric);
        }
        return metric;
    }

    public Overview overview(Window w) {
        long len = w.lengthDays();
        Window prev = new Window(w.from().minusDays(len), w.from().minusDays(1));

        long membersActive = metrics.latestValue("members_active", w.from(), w.to());
        long actions = metrics.total("actions", w.from(), w.to());
        long pointsEarned = metrics.total("points_earned", w.from(), w.to());
        long pointsSpent = metrics.total("points_spent", w.from(), w.to());
        long pointsExpired = metrics.total("points_expired", w.from(), w.to());
        long membersNew = metrics.total("members_new", w.from(), w.to());
        long tierChanges = metrics.total("tier_changes", w.from(), w.to());
        long redemptions = metrics.total("redemptions", w.from(), w.to());
        long plays = metrics.total("plays", w.from(), w.to());
        long wins = metrics.total("wins", w.from(), w.to());
        // docs/10 §9: «membri totali» = storico sintetico (3 100 → 3 480) + i 12 reali del seed + i registrati.
        long membersTotal = metrics.latestValueUpTo("members_total", w.to())
                + SEED_MEMBERS + events.distinctMembers("member.registered");

        Deltas deltas = new Deltas(
                delta(membersActive, metrics.latestValue("members_active", prev.from(), prev.to())),
                delta(actions, metrics.total("actions", prev.from(), prev.to())),
                delta(pointsEarned, metrics.total("points_earned", prev.from(), prev.to())),
                delta(pointsSpent, metrics.total("points_spent", prev.from(), prev.to())),
                delta(redemptions, metrics.total("redemptions", prev.from(), prev.to())),
                delta(plays, metrics.total("plays", prev.from(), prev.to())));

        return new Overview(w.from(), w.to(), membersTotal, membersActive, actions,
                pointsEarned, pointsSpent, pointsExpired, membersNew, tierChanges,
                redemptions, plays, wins, deltas);
    }

    /** Serie {@code day|week} (maiuscole indifferenti); altra granularità ⇒ 400 (Q-326). */
    public TimeSeries timeseries(String metric, LocalDate from, LocalDate to, String granularity) {
        String g = granularity == null ? "day" : granularity.toLowerCase(Locale.ROOT);
        if (!"day".equals(g) && !"week".equals(g)) {
            throw LhException.badRequest("Granularità non valida (attesa day|week): " + granularity);
        }
        List<Point> daily = metrics.timeseries(metric(metric), MetricRepository.TOTAL, from, to);
        if ("week".equals(g)) {
            return new TimeSeries(metric, "week", bucketByWeek(daily));
        }
        return new TimeSeries(metric, "day", daily);
    }

    public Breakdown breakdown(String metric, String dimension, LocalDate from, LocalDate to, int limit) {
        if (limit < 1) {
            throw LhException.badRequest("Parametro limit non valido (atteso ≥ 1): " + limit);
        }
        List<Slice> slices = metrics.breakdown(metric(metric), dimension, from, to, limit);
        long total = slices.stream().mapToLong(Slice::value).sum();
        return new Breakdown(metric, dimension, total, slices);
    }

    /** Aggrega i punti giornalieri per settimana ISO (etichetta = lunedì della settimana). */
    private static List<Point> bucketByWeek(List<Point> daily) {
        WeekFields wf = WeekFields.of(Locale.ITALY);
        List<Point> out = new ArrayList<>();
        LocalDate weekStart = null;
        long sum = 0;
        boolean synthetic = false;
        for (Point p : daily) {
            LocalDate monday = p.day().with(wf.dayOfWeek(), 1);
            if (weekStart == null) {
                weekStart = monday;
            }
            if (!monday.equals(weekStart)) {
                out.add(new Point(weekStart, sum, synthetic));
                weekStart = monday;
                sum = 0;
                synthetic = false;
            }
            sum += p.value();
            synthetic |= p.synthetic();
        }
        if (weekStart != null) {
            out.add(new Point(weekStart, sum, synthetic));
        }
        return out;
    }

    private static Delta delta(long current, long previous) {
        Double pct = previous == 0 ? null : (current - previous) * 100.0 / previous;
        return new Delta(current - previous, pct);
    }

    public record Window(LocalDate from, LocalDate to) {
        long lengthDays() {
            return to.toEpochDay() - from.toEpochDay() + 1;
        }
    }

    public record Delta(long abs, Double pct) {
    }

    public record Deltas(Delta membersActive30d, Delta actions, Delta pointsEarned,
                         Delta pointsSpent, Delta redemptions, Delta plays) {
    }

    public record Overview(LocalDate from, LocalDate to, long membersTotal, long membersActive30d,
                           long actions, long pointsEarned, long pointsSpent, long pointsExpired,
                           long membersNew, long tierChanges, long redemptions, long plays, long wins,
                           Deltas deltas) {
    }

    public record TimeSeries(String metric, String granularity, List<Point> points) {
    }

    public record Breakdown(String metric, String dimension, long total, List<Slice> slices) {
    }
}
