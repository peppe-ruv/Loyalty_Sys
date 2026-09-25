package io.loyaltyhub.common.condition;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cast tipizzato «più sicuro» delle condizioni (docs/03 §3.3; Q-215, Q-216 decise): una sola politica per i quattro
 * valutatori (campaign {@code ConditionEvaluator}, member {@code SegmentCriteria}, gamification {@code AchievementRules},
 * engagement {@code DataCondition}). Classe pura, senza Spring né stato.
 *
 * <ol>
 *   <li>Il tipo <b>bersaglio</b> è quello del valore osservato nel dato (numero, testo, booleano, data {@code AAAA-MM-GG},
 *   istante ISO-8601 con fuso). Si converte il valore della <em>regola</em> verso quel tipo, mai il contrario: dal dato
 *   non si estrae mai un numero da un testo.</li>
 *   <li>Conversioni strette, tutto-o-niente: numero ← numero JSON o testo che rispetta esattamente
 *   {@code ^-?\d+(\.\d+)?$} (niente spazi, esponente, separatori locali, {@code +} iniziale), confronto con
 *   {@link BigDecimal#compareTo} (mai {@code double}); booleano ← booleano JSON o esattamente {@code "true"}/{@code "false"};
 *   data ← {@code AAAA-MM-GG} valida nel calendario; istante ← data e ora ISO-8601 con fuso ({@code Z} o
 *   {@code ±hh:mm}); testo ← solo testo (nessun numero diventa testo). Date e istanti si confrontano solo con la stessa
 *   granularità (una data non diventa un istante né viceversa).</li>
 *   <li>Conversione fallita → la foglia è <b>falsa</b> per ogni comparatore, negazioni comprese ({@code neq},
 *   {@code nin}, {@code ncontains}). Campo assente o {@code null} → falsa tranne {@code nexists}: lo gestisce il
 *   chiamante, che risolve il campo.</li>
 *   <li>Comparatore sconosciuto → falsa (Q-218, Q-295, Q-179).</li>
 * </ol>
 * I comparatori di testo ({@code contains}, {@code ncontains}, {@code startsWith}) valgono solo su un testo che non è una
 * data o un istante (il tipo bersaglio di {@code "2026-02-28"} è la data, non il testo).
 */
public final class TypedCast {

    /** Tipo scalare di un valore osservato o dichiarato. */
    public enum Type { NUMBER, STRING, BOOLEAN, DATE, INSTANT }

    /** Valore osservato con il suo tipo: {@link BigDecimal}, {@link String}, {@link Boolean}, {@link LocalDate}, {@link Instant}. */
    public record Typed(Type type, Object value) {
    }

    /** I 14 comparatori di docs/03 §3.3. */
    public static final List<String> COMPARATORS = List.of("eq", "neq", "gt", "gte", "lt", "lte", "in", "nin", "contains",
            "ncontains", "exists", "nexists", "between", "startsWith");

    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:\\d{2})$");

    private TypedCast() {
    }

    // ---------- tipo del valore osservato ----------

    /**
     * Tipo del valore osservato (già risolto dal chiamante): numeri Java o JSON → {@link Type#NUMBER}; testo che è una
     * data o un istante ISO stretti → {@link Type#DATE}/{@link Type#INSTANT}, altrimenti {@link Type#STRING}.
     * {@code null}, elenchi, oggetti e numeri non finiti → {@code null} (non scalare).
     */
    public static Typed typed(Object actual) {
        if (actual == null) {
            return null;
        }
        if (actual instanceof JsonNode n) {
            return typedJson(n);
        }
        if (actual instanceof BigDecimal b) {
            return new Typed(Type.NUMBER, b);
        }
        if (actual instanceof Double || actual instanceof Float) {
            double d = ((Number) actual).doubleValue();
            return Double.isFinite(d) ? new Typed(Type.NUMBER, BigDecimal.valueOf(d)) : null;
        }
        if (actual instanceof Long || actual instanceof Integer || actual instanceof Short || actual instanceof Byte
                || actual instanceof BigInteger) {
            return new Typed(Type.NUMBER, new BigDecimal(actual.toString()));
        }
        if (actual instanceof Boolean b) {
            return new Typed(Type.BOOLEAN, b);
        }
        if (actual instanceof LocalDate d) {
            return new Typed(Type.DATE, d);
        }
        if (actual instanceof Instant i) {
            return new Typed(Type.INSTANT, i);
        }
        if (actual instanceof OffsetDateTime o) {
            return new Typed(Type.INSTANT, o.toInstant());
        }
        if (actual instanceof ZonedDateTime z) {
            return new Typed(Type.INSTANT, z.toInstant());
        }
        if (actual instanceof String s) {
            Optional<LocalDate> d = parseDate(s);
            if (d.isPresent()) {
                return new Typed(Type.DATE, d.get());
            }
            Optional<Instant> i = parseInstant(s);
            if (i.isPresent()) {
                return new Typed(Type.INSTANT, i.get());
            }
            return new Typed(Type.STRING, s);
        }
        return null;
    }

    private static Typed typedJson(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isNumber()) {
            return new Typed(Type.NUMBER, n.decimalValue());
        }
        if (n.isBoolean()) {
            return new Typed(Type.BOOLEAN, n.asBoolean());
        }
        if (n.isString()) {
            return typed(n.asString());
        }
        return null;
    }

    // ---------- cast del valore della regola ----------

    /** Valore della regola convertito nel tipo bersaglio; vuoto se la conversione (stretta) non riesce. */
    public static Optional<Object> cast(JsonNode value, Type target) {
        if (value == null || target == null) {
            return Optional.empty();
        }
        return switch (target) {
            case NUMBER -> toNumber(value).map(x -> (Object) x);
            case BOOLEAN -> toBoolean(value).map(x -> (Object) x);
            case DATE -> toDate(value).map(x -> (Object) x);
            case INSTANT -> toInstant(value).map(x -> (Object) x);
            case STRING -> value.isString() ? Optional.of(value.asString()) : Optional.empty();
        };
    }

    /** Vero se il valore della regola si converte nel tipo bersaglio. */
    public static boolean castsTo(JsonNode value, Type target) {
        return cast(value, target).isPresent();
    }

    /** Numero ← numero JSON o testo {@code ^-?\d+(\.\d+)?$} esatto. */
    public static Optional<BigDecimal> toNumber(JsonNode value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isNumber()) {
            return Optional.of(value.decimalValue());
        }
        if (value.isString() && NUMBER.matcher(value.asString()).matches()) {
            return Optional.of(new BigDecimal(value.asString()));
        }
        return Optional.empty();
    }

    /** Booleano ← booleano JSON o esattamente {@code "true"}/{@code "false"}. */
    public static Optional<Boolean> toBoolean(JsonNode value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isBoolean()) {
            return Optional.of(value.asBoolean());
        }
        if (value.isString()) {
            String s = value.asString();
            if ("true".equals(s) || "false".equals(s)) {
                return Optional.of(Boolean.parseBoolean(s));
            }
        }
        return Optional.empty();
    }

    /** Data ← testo {@code AAAA-MM-GG} valido nel calendario (niente 29 febbraio di un anno non bisestile). */
    public static Optional<LocalDate> toDate(JsonNode value) {
        return value != null && value.isString() ? parseDate(value.asString()) : Optional.empty();
    }

    /** Istante ← testo ISO-8601 con data, ora e fuso ({@code Z} o {@code ±hh:mm}). */
    public static Optional<Instant> toInstant(JsonNode value) {
        return value != null && value.isString() ? parseInstant(value.asString()) : Optional.empty();
    }

    static Optional<LocalDate> parseDate(String s) {
        if (s == null || !DATE.matcher(s).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    static Optional<Instant> parseInstant(String s) {
        if (s == null || !INSTANT.matcher(s).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // ---------- confronti ----------

    /**
     * Foglia su un valore osservato <b>scalare e presente</b> (il chiamante gestisce campo assente ed elenchi). Il valore
     * della regola è convertito nel tipo del valore osservato; conversione fallita o comparatore sconosciuto → falso.
     */
    public static boolean compare(String cmp, Object actual, JsonNode value) {
        if (cmp == null) {
            return false;
        }
        Typed a = typed(actual);
        if (a == null) {
            return false;
        }
        return switch (cmp) {
            case "exists" -> true;
            case "nexists" -> false;
            case "eq" -> scalar(value) && cast(value, a.type()).map(v -> same(a, v)).orElse(false);
            case "neq" -> scalar(value) && cast(value, a.type()).map(v -> !same(a, v)).orElse(false);
            case "gt" -> order(a, value, c -> c > 0);
            case "gte" -> order(a, value, c -> c >= 0);
            case "lt" -> order(a, value, c -> c < 0);
            case "lte" -> order(a, value, c -> c <= 0);
            case "in" -> inList(a, value, true);
            case "nin" -> inList(a, value, false);
            case "between" -> between(a, value);
            case "contains" -> text(a, value) && ((String) a.value()).contains(value.asString());
            case "ncontains" -> text(a, value) && !((String) a.value()).contains(value.asString());
            case "startsWith" -> text(a, value) && ((String) a.value()).startsWith(value.asString());
            default -> false;
        };
    }

    /** Uguaglianza tipizzata: il valore della regola convertito nel tipo del valore osservato. */
    public static boolean equalsCast(Object actual, JsonNode value) {
        Typed a = typed(actual);
        return a != null && scalar(value) && cast(value, a.type()).map(v -> same(a, v)).orElse(false);
    }

    /**
     * {@code contains} su un elenco osservato: vero se almeno un elemento è uguale al valore della regola convertito nel
     * tipo di quell'elemento. Elementi {@code null} ignorati.
     */
    public static boolean listContains(List<?> list, JsonNode value) {
        if (list == null || !scalar(value)) {
            return false;
        }
        for (Object el : list) {
            if (el != null && equalsCast(el, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ncontains} su un elenco osservato: vero se il valore della regola si converte nel tipo di <b>ogni</b>
     * elemento e non è uguale a nessuno (tutto-o-niente: un elemento di tipo incompatibile rende falsa la foglia).
     * Elenco vuoto → vero. Elementi {@code null} ignorati.
     */
    public static boolean listNotContains(List<?> list, JsonNode value) {
        if (list == null || !scalar(value)) {
            return false;
        }
        for (Object el : list) {
            if (el == null) {
                continue;
            }
            Typed t = typed(el);
            if (t == null) {
                return false;
            }
            Optional<Object> v = cast(value, t.type());
            if (v.isEmpty() || same(t, v.get())) {
                return false;
            }
        }
        return true;
    }

    private static boolean scalar(JsonNode value) {
        return value != null && !value.isNull() && !value.isMissingNode() && !value.isArray() && !value.isObject();
    }

    private static boolean same(Typed a, Object v) {
        if (a.type() == Type.NUMBER) {
            return ((BigDecimal) a.value()).compareTo((BigDecimal) v) == 0;
        }
        return a.value().equals(v);
    }

    private static boolean orderable(Type t) {
        return t == Type.NUMBER || t == Type.DATE || t == Type.INSTANT;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareTo(Typed a, Object v) {
        return ((Comparable) a.value()).compareTo(v);
    }

    private static boolean order(Typed a, JsonNode value, java.util.function.IntPredicate test) {
        if (!orderable(a.type()) || !scalar(value)) {
            return false;
        }
        return cast(value, a.type()).map(v -> test.test(compareTo(a, v))).orElse(false);
    }

    /** {@code in}/{@code nin}: elenco di valori tutti convertibili (tutto-o-niente), altrimenti falso. */
    private static boolean inList(Typed a, JsonNode values, boolean in) {
        if (values == null || !values.isArray()) {
            return false;
        }
        boolean found = false;
        for (JsonNode v : values) {
            if (!scalar(v)) {
                return false;
            }
            Optional<Object> c = cast(v, a.type());
            if (c.isEmpty()) {
                return false;
            }
            found |= same(a, c.get());
        }
        return in == found;
    }

    /** {@code between [min, max]}: esattamente due estremi convertibili, inclusi; estremi invertiti → falso. */
    private static boolean between(Typed a, JsonNode value) {
        if (!orderable(a.type()) || value == null || !value.isArray() || value.size() != 2
                || !scalar(value.get(0)) || !scalar(value.get(1))) {
            return false;
        }
        Optional<Object> lo = cast(value.get(0), a.type());
        Optional<Object> hi = cast(value.get(1), a.type());
        return lo.isPresent() && hi.isPresent() && compareTo(a, lo.get()) >= 0 && compareTo(a, hi.get()) <= 0;
    }

    /** Comparatori di testo: valore osservato di tipo testo (non data/istante) e valore della regola testuale. */
    private static boolean text(Typed a, JsonNode value) {
        return a.type() == Type.STRING && value != null && value.isString();
    }
}
