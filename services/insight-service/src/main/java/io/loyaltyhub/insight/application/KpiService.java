package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.MetricRepository;
import io.loyaltyhub.insight.infra.MetricRepository.Point;
import io.loyaltyhub.insight.infra.MetricRepository.Slice;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KPI di programma per BO-01 (docs/servizi/insight-service.md §3, docs/08 §BO-01). Legge {@code metric_daily}
 * (dati reali + storico sintetico) e l'event store. In M2 sono valorizzate le metriche prodotte da M1/M2:
 * azioni, punti emessi, membri; premi/gioco (redemptions/plays/wins) restano a 0 fino a M4/M5.
 */
@Service
public class KpiService {

    /** Membri caricati dal seed (docs/10 §2): base per il totale, i nuovi arrivano dagli eventi. */
    private static final long SEED_MEMBERS = 12;

    private final MetricRepository metrics;
    private final EventStoreRepository events;
    private final Clock clock;

    public KpiService(MetricRepository metrics, EventStoreRepository events, Clock clock) {
        this.metrics = metrics;
        this.events = events;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Normalizza la finestra: se {@code from/to} assenti usa gli ultimi {@code days} giorni fino a oggi. */
    public Window window(LocalDate from, LocalDate to, int days) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusDays(Math.max(1, days) - 1L);
        return new Window(start, end);
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
        long membersTotal = SEED_MEMBERS + events.distinctMembers("member.registered");

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

    public TimeSeries timeseries(String metric, LocalDate from, LocalDate to, String granularity) {
        List<Point> daily = metrics.timeseries(metric, MetricRepository.TOTAL, from, to);
        if ("week".equalsIgnoreCase(granularity)) {
            return new TimeSeries(metric, "week", bucketByWeek(daily));
        }
        return new TimeSeries(metric, "day", daily);
    }

    public Breakdown breakdown(String metric, String dimension, LocalDate from, LocalDate to, int limit) {
        List<Slice> slices = metrics.breakdown(metric, dimension, from, to, Math.max(1, limit));
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

    public record Deltas(Delta membersActive, Delta actions, Delta pointsEarned,
                         Delta pointsSpent, Delta redemptions, Delta plays) {
    }

    public record Overview(LocalDate from, LocalDate to, long membersTotal, long membersActive,
                           long actions, long pointsEarned, long pointsSpent, long pointsExpired,
                           long membersNew, long tierChanges, long redemptions, long plays, long wins,
                           Deltas deltas) {
    }

    public record TimeSeries(String metric, String granularity, List<Point> points) {
    }

    public record Breakdown(String metric, String dimension, long total, List<Slice> slices) {
    }
}
