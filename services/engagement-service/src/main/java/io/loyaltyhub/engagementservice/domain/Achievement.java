package io.loyaltyhub.engagementservice.domain;

import java.util.List;
import java.util.Map;

/**
 * Achievement (RF-90): contatore di progresso su transazioni o eventi (occorrenze o somma di un attributo), con
 * obiettivo OVERALL (in qualunque momento), LAST_DAYS (finestra mobile) o CONSECUTIVE (streak per periodo di calendario),
 * limite di eventi contati per periodo, limite di completamenti per periodo o totale. Al completamento emette l'azione
 * interna ACHIEVEMENT_COMPLETED (e ACHIEVEMENT_PROGRESSED a ogni avanzamento) che le campagne possono premiare.
 * Modificare le regole dopo la partecipazione azzera i progressi: le definizioni sono versionate.
 */
public record Achievement(
        String id, String version, String name, boolean active,
        String actionType,
        List<Condition> conditions,
        Metric metric, String attribute,
        Goal goal,
        Limit eventLimit,
        Limit completionLimit
) {
    public enum Metric { OCCURRENCES, ATTRIBUTE_SUM, UNIQUE_ATTRIBUTE_VALUES }
    public enum Period { HOUR, DAY, WEEK, MONTH, YEAR, TOTAL }
    public record Condition(String attribute, String op, String value) {}
    /**
     * @param type OVERALL: raggiungi {@code target} in qualunque momento; LAST_DAYS: dentro gli ultimi {@code windowDays};
     *             CONSECUTIVE: almeno {@code target} per {@code period} per {@code consecutivePeriods} periodi consecutivi.
     */
    public record Goal(Type type, double target, int windowDays, Period period, int consecutivePeriods) {
        public enum Type { OVERALL, LAST_DAYS, CONSECUTIVE }
        public static Goal overall(double target) { return new Goal(Type.OVERALL, target, 0, null, 0); }
        public static Goal lastDays(double target, int days) { return new Goal(Type.LAST_DAYS, target, days, null, 0); }
        public static Goal streak(double perPeriod, Period period, int periods) { return new Goal(Type.CONSECUTIVE, perPeriod, 0, period, periods); }
    }
    /** {@code max} eventi (o completamenti) per {@code period}; 0 = illimitati. */
    public record Limit(int max, Period period) {
        public static final Limit NONE = new Limit(0, Period.TOTAL);
    }

    public boolean matches(String actionType, Map<String, Object> attrs) {
        if (!this.actionType.equals(actionType)) return false;
        if (conditions == null) return true;
        for (Condition c : conditions) {
            Object v = attrs == null ? null : attrs.get(c.attribute());
            String s = v == null ? null : v.toString();
            boolean ok = switch (c.op()) {
                case "EQ" -> java.util.Objects.equals(s, c.value());
                case "NE" -> !java.util.Objects.equals(s, c.value());
                case "GT" -> s != null && new java.math.BigDecimal(s).compareTo(new java.math.BigDecimal(c.value())) > 0;
                case "GTE" -> s != null && new java.math.BigDecimal(s).compareTo(new java.math.BigDecimal(c.value())) >= 0;
                case "LT" -> s != null && new java.math.BigDecimal(s).compareTo(new java.math.BigDecimal(c.value())) < 0;
                case "IN" -> s != null && List.of(c.value().split(",")).contains(s);
                default -> false;
            };
            if (!ok) return false;
        }
        return true;
    }

    public double valueOf(Map<String, Object> attrs) {
        if (metric == Metric.OCCURRENCES) return 1;
        Object v = attrs == null ? null : attrs.get(attribute);
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
