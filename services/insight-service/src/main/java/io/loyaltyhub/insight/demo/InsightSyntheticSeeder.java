package io.loyaltyhub.insight.demo;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.insight.infra.MetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Storico sintetico di {@code metric_daily} (F-INS-04, docs/servizi/insight-service.md §5): genera al seed
 * 90 giorni di metriche con {@code synthetic=true} così BO-01 non è mai vuota appena accesa (docs/12 §M2.4,
 * CLAUDE.md §9). Curva con trend +0,4%/giorno, stagionalità settimanale (+35% sab/dom su {@code points_earned}),
 * picco "campagna estiva" al giorno -30 e rumore ±12%. Generatore con seme fisso => reset deterministico
 * (docs/10 §1.3). I dati reali del giorno si sommano a questi (UPSERT incrementale, docs §5).
 * Possiede interamente {@code metric_daily}: azzera e rigenera in un colpo solo, indipendente dall'ordine di reset.
 */
@Component
@Profile("demo")
public class InsightSyntheticSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(InsightSyntheticSeeder.class);

    private final SeedLoader seed;
    private final MetricRepository metrics;
    private final Clock clock;

    public InsightSyntheticSeeder(SeedLoader seed, MetricRepository metrics, Clock clock) {
        this.seed = seed;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "insight-synthetic";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        metrics.deleteAll();

        JsonNode cfg = seed.readTree("insight-synthetic.json");
        int days = cfg.path("days").asInt(90);
        double trendPerDay = cfg.path("trendPerDay").asDouble(0.004);
        double noise = cfg.path("noise").asDouble(0.12);
        double weekendUplift = cfg.path("weekendUpliftPointsEarned").asDouble(0.35);
        JsonNode peak = cfg.path("peak");
        int peakOffset = peak.path("dayOffset").asInt(-30);
        double peakWidth = peak.path("width").asDouble(6.0);
        double peakUplift = peak.path("uplift").asDouble(0.6);

        // Seme fisso: la sequenza di rumore è riproducibile perché consumata in ordine deterministico
        // (metriche nell'ordine del file, giorni crescenti) — docs/10 §1.3.
        Random rnd = new Random(cfg.path("seed").asLong(20240701L));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        JsonNode metricDefs = cfg.path("metrics");

        int rows = 0;
        for (Map.Entry<String, JsonNode> e : metricDefs.properties()) {
            String metric = e.getKey();
            JsonNode def = e.getValue();
            double baseline = def.path("baseline").asDouble(0);
            boolean peakSensitive = def.path("peakSensitive").asBoolean(false);
            boolean gauge = def.path("gauge").asBoolean(false);
            boolean isPointsEarned = "points_earned".equals(metric);
            JsonNode bySource = def.get("bySource");
            JsonNode byCurrency = def.get("byCurrency");

            for (int t = 0; t < days; t++) {
                int offset = -(days - t);            // -90 .. -1 rispetto a oggi
                LocalDate day = today.plusDays(offset);

                double trend = Math.pow(1.0 + trendPerDay, t);
                double weekend = isPointsEarned && isWeekend(day) ? (1.0 + weekendUplift) : 1.0;
                double peakFactor = peakSensitive
                        ? 1.0 + peakUplift * Math.exp(-Math.pow(offset - peakOffset, 2) / (2.0 * peakWidth * peakWidth))
                        : 1.0;
                double jitter = 1.0 + (rnd.nextDouble() * 2.0 - 1.0) * noise;

                // Il gauge (es. membri attivi) oscilla attorno al baseline senza trend cumulativo;
                // le metriche di flusso seguono trend, stagionalità, picco e rumore.
                double raw = gauge
                        ? baseline * (1.0 + (rnd.nextDouble() * 2.0 - 1.0) * (noise / 2.0))
                        : baseline * trend * weekend * peakFactor * jitter;
                long total = Math.max(0, Math.round(raw));

                metrics.putSynthetic(day, metric, MetricRepository.TOTAL, MetricRepository.TOTAL, total);
                rows++;

                if (bySource != null) {
                    rows += allocate(day, metric, "source", bySource, total);
                }
                if (byCurrency != null) {
                    rows += allocate(day, metric, "currency", byCurrency, total);
                }
            }
        }
        log.info("Storico sintetico insight rigenerato (profilo demo): {} righe metric_daily, {} giorni", rows, days);
    }

    /** Ripartisce {@code total} sui valori della dimensione secondo le frazioni; il primo assorbe il resto (somma == total). */
    private int allocate(LocalDate day, String metric, String dimension, JsonNode fractions, long total) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(fractions.properties());
        long assignedToRest = 0;
        for (int i = 1; i < entries.size(); i++) {
            assignedToRest += Math.round(total * entries.get(i).getValue().asDouble(0));
        }
        for (int i = 0; i < entries.size(); i++) {
            long part = i == 0
                    ? Math.max(0, total - assignedToRest)
                    : Math.round(total * entries.get(i).getValue().asDouble(0));
            metrics.putSynthetic(day, metric, dimension, entries.get(i).getKey(), part);
        }
        return entries.size();
    }

    private static boolean isWeekend(LocalDate day) {
        return switch (day.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> true;
            default -> false;
        };
    }
}
