package io.loyaltyhub.insight.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Metriche giornaliere (docs/servizi/insight-service.md §2). Incremento idempotente per riga
 * ({@code UPSERT}); i dati reali si sommano a quelli sintetici dello stesso giorno (docs §5).
 */
@Repository
public class MetricRepository {

    public static final String TOTAL = "";

    private final JdbcClient jdbc;

    public MetricRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Somma {@code delta} alla cella (giorno, metrica, dimensione, valore); crea la riga se assente (reale). */
    public void increment(LocalDate day, String metric, String dimension, String dimValue, long delta) {
        jdbc.sql("""
                        INSERT INTO metric_daily (day, metric, dimension, dim_value, value, synthetic)
                        VALUES (?, ?, ?, ?, ?, false)
                        ON CONFLICT (day, metric, dimension, dim_value)
                        DO UPDATE SET value = metric_daily.value + EXCLUDED.value
                        """)
                .params(day, metric, dimension == null ? TOTAL : dimension,
                        dimValue == null ? TOTAL : dimValue, BigDecimal.valueOf(delta))
                .update();
    }

    /** Inserisce una cella sintetica dello storico (seed); sovrascrive se rigenerata. */
    public void putSynthetic(LocalDate day, String metric, String dimension, String dimValue, long value) {
        jdbc.sql("""
                        INSERT INTO metric_daily (day, metric, dimension, dim_value, value, synthetic)
                        VALUES (?, ?, ?, ?, ?, true)
                        ON CONFLICT (day, metric, dimension, dim_value)
                        DO UPDATE SET value = EXCLUDED.value, synthetic = true
                        """)
                .params(day, metric, dimension == null ? TOTAL : dimension,
                        dimValue == null ? TOTAL : dimValue, BigDecimal.valueOf(value))
                .update();
    }

    /** Totale di una metrica (dimensione totale) nell'intervallo [from, to]. */
    public long total(String metric, LocalDate from, LocalDate to) {
        Long v = jdbc.sql("""
                        SELECT coalesce(sum(value), 0) FROM metric_daily
                        WHERE metric = ? AND dimension = '' AND day BETWEEN ? AND ?
                        """)
                .params(metric, from, to).query(Long.class).single();
        return v == null ? 0 : v;
    }

    /** Serie giornaliera del totale di una metrica (o di una dimensione se {@code dimension} non è vuota). */
    public List<Point> timeseries(String metric, String dimension, LocalDate from, LocalDate to) {
        String dim = dimension == null ? TOTAL : dimension;
        return jdbc.sql("""
                        SELECT day, coalesce(sum(value), 0) AS value, bool_or(synthetic) AS synthetic
                        FROM metric_daily
                        WHERE metric = ? AND dimension = ? AND day BETWEEN ? AND ?
                        GROUP BY day ORDER BY day
                        """)
                .params(metric, dim, from, to).query(MetricRepository::mapPoint).list();
    }

    /** Ripartizione di una metrica per {@code dim_value} nell'intervallo (top N). */
    public List<Slice> breakdown(String metric, String dimension, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                        SELECT dim_value, coalesce(sum(value), 0) AS value
                        FROM metric_daily
                        WHERE metric = ? AND dimension = ? AND dimension <> '' AND day BETWEEN ? AND ?
                        GROUP BY dim_value ORDER BY value DESC LIMIT ?
                        """)
                .params(metric, dimension, from, to, limit).query(MetricRepository::mapSlice).list();
    }

    /** Valore più recente di una metrica-gauge (dimensione totale) entro [from, to]; 0 se assente. */
    public long latestValue(String metric, LocalDate from, LocalDate to) {
        Long v = jdbc.sql("""
                        SELECT value FROM metric_daily
                        WHERE metric = ? AND dimension = '' AND day BETWEEN ? AND ?
                        ORDER BY day DESC LIMIT 1
                        """)
                .params(metric, from, to).query(Long.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    /** Valore più recente di una metrica-gauge (dimensione totale) fino a {@code to} incluso; 0 se assente. */
    public long latestValueUpTo(String metric, LocalDate to) {
        Long v = jdbc.sql("""
                        SELECT value FROM metric_daily
                        WHERE metric = ? AND dimension = '' AND day <= ?
                        ORDER BY day DESC LIMIT 1
                        """)
                .params(metric, to).query(Long.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM metric_daily").update();
    }

    public record Point(LocalDate day, long value, boolean synthetic) {
    }

    public record Slice(String dimValue, long value) {
    }

    private static Point mapPoint(ResultSet rs, int n) throws SQLException {
        return new Point(rs.getObject("day", LocalDate.class), rs.getLong("value"), rs.getBoolean("synthetic"));
    }

    private static Slice mapSlice(ResultSet rs, int n) throws SQLException {
        return new Slice(rs.getString("dim_value"), rs.getLong("value"));
    }
}
