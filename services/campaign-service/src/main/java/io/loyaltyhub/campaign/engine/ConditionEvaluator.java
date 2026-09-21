package io.loyaltyhub.campaign.engine;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Valuta l'albero di condizioni di una campagna (docs/03 §3.3) sullo spazio {@code data/member/context/history}.
 * Campo assente → foglia falsa (tranne {@code nexists}); tipi incompatibili → falsa, mai eccezione.
 * Su array ({@code items[*].x}): vero se almeno un elemento soddisfa.
 */
public final class ConditionEvaluator {

    /** {@code Europe/Rome} per {@code context.dayOfWeek/hour/date} (docs/03 §3.3). */
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Object ABSENT = new Object();

    private final EvalAction action;
    private final MemberSnapshot member;
    private final Counters counters;

    public ConditionEvaluator(EvalAction action, MemberSnapshot member, Counters counters) {
        this.action = action;
        this.member = member;
        this.counters = counters;
    }

    public record Result(boolean pass, List<Evaluation.FailedCondition> failed) {
    }

    public Result evaluate(JsonNode node) {
        List<Evaluation.FailedCondition> failed = new ArrayList<>();
        boolean pass = eval(node, failed);
        return new Result(pass, failed);
    }

    private boolean eval(JsonNode node, List<Evaluation.FailedCondition> failed) {
        if (node == null || node.isNull() || node.isEmpty()) {
            return true; // nessuna condizione = sempre vero
        }
        if (node.has("op")) {
            String op = node.path("op").asString("all");
            JsonNode rules = node.path("rules");
            return switch (op) {
                case "all" -> allOf(rules, failed);
                case "any" -> anyOf(rules, failed);
                case "not" -> !allOf(rules, new ArrayList<>());
                default -> allOf(rules, failed);
            };
        }
        // foglia
        return leaf(node, failed);
    }

    private boolean allOf(JsonNode rules, List<Evaluation.FailedCondition> failed) {
        boolean ok = true;
        if (rules != null) {
            for (JsonNode r : rules) {
                if (!eval(r, failed)) {
                    ok = false;
                }
            }
        }
        return ok;
    }

    private boolean anyOf(JsonNode rules, List<Evaluation.FailedCondition> failed) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        List<Evaluation.FailedCondition> local = new ArrayList<>();
        for (JsonNode r : rules) {
            if (eval(r, local)) {
                return true;
            }
        }
        failed.addAll(local);
        return false;
    }

    private boolean leaf(JsonNode node, List<Evaluation.FailedCondition> failed) {
        String field = node.path("field").asString(null);
        String cmp = node.path("cmp").asString("eq");
        JsonNode value = node.get("value");
        if (field == null) {
            return true;
        }
        Object actual = resolve(field);
        boolean ok = compare(cmp, actual, value);
        if (!ok) {
            failed.add(new Evaluation.FailedCondition(field, cmp, jsonToObject(value),
                    actual == ABSENT ? null : actual));
        }
        return ok;
    }

    // ---------- comparatori ----------

    @SuppressWarnings("unchecked")
    private boolean compare(String cmp, Object actual, JsonNode value) {
        if ("exists".equals(cmp)) {
            return actual != ABSENT;
        }
        if ("nexists".equals(cmp)) {
            return actual == ABSENT;
        }
        if (actual == ABSENT) {
            return false; // campo assente → foglia falsa
        }
        // Su array: vero se almeno un elemento soddisfa (tranne le set-op che gestiscono la lista intera).
        if (actual instanceof List<?> list && !isSetOp(cmp)) {
            for (Object el : list) {
                if (compareScalar(cmp, el, value)) {
                    return true;
                }
            }
            return false;
        }
        return compareScalar(cmp, actual, value);
    }

    private boolean isSetOp(String cmp) {
        return "in".equals(cmp) || "nin".equals(cmp) || "contains".equals(cmp) || "ncontains".equals(cmp);
    }

    private boolean compareScalar(String cmp, Object actual, JsonNode value) {
        return switch (cmp) {
            case "eq" -> equalsValue(actual, value);
            case "neq" -> !equalsValue(actual, value);
            case "gt" -> numeric(actual, value, c -> c > 0);
            case "gte" -> numeric(actual, value, c -> c >= 0);
            case "lt" -> numeric(actual, value, c -> c < 0);
            case "lte" -> numeric(actual, value, c -> c <= 0);
            case "in" -> inList(actual, value);
            case "nin" -> !inList(actual, value);
            case "contains" -> contains(actual, value);
            case "ncontains" -> !contains(actual, value);
            case "between" -> between(actual, value);
            case "startsWith" -> actual != null && value != null
                    && actual.toString().startsWith(value.asString(""));
            default -> false;
        };
    }

    private boolean equalsValue(Object actual, JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (actual instanceof Number a && value.isNumber()) {
            return a.doubleValue() == value.asDouble();
        }
        if (actual instanceof Boolean a && value.isBoolean()) {
            return a == value.asBoolean();
        }
        return actual.toString().equals(value.asString(""));
    }

    private boolean numeric(Object actual, JsonNode value, java.util.function.IntPredicate cmp) {
        Double a = toDouble(actual);
        if (a == null || value == null || !value.isNumber()) {
            return false;
        }
        return cmp.test(Double.compare(a, value.asDouble()));
    }

    private boolean inList(Object actual, JsonNode value) {
        if (value == null || !value.isArray()) {
            return false;
        }
        for (JsonNode v : value) {
            if (equalsValue(actual, v)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(Object actual, JsonNode value) {
        if (actual instanceof List<?> list) {
            for (Object el : list) {
                if (equalsValue(el, value)) {
                    return true;
                }
            }
            return false;
        }
        return actual != null && value != null && actual.toString().contains(value.asString(""));
    }

    private boolean between(Object actual, JsonNode value) {
        Double a = toDouble(actual);
        if (a == null || value == null || !value.isArray() || value.size() < 2) {
            return false;
        }
        return a >= value.get(0).asDouble() && a <= value.get(1).asDouble();
    }

    // ---------- risoluzione dei campi ----------

    private Object resolve(String field) {
        int dot = field.indexOf('.');
        String space = dot < 0 ? field : field.substring(0, dot);
        String rest = dot < 0 ? "" : field.substring(dot + 1);
        return switch (space) {
            case "data" -> resolveData(rest);
            case "member" -> resolveMember(rest);
            case "context" -> resolveContext(rest);
            case "history" -> resolveHistory(rest);
            default -> ABSENT;
        };
    }

    private Object resolveData(String path) {
        if (action.data() == null) {
            return ABSENT;
        }
        return navigate(action.data(), path);
    }

    /** Naviga un path {@code a.b}, {@code arr[*].c} su un JsonNode; ritorna scalare, {@code List} o ABSENT. */
    private Object navigate(JsonNode node, String path) {
        List<JsonNode> current = new ArrayList<>();
        current.add(node);
        for (String seg : path.split("\\.")) {
            boolean wildcard = seg.endsWith("[*]");
            String key = wildcard ? seg.substring(0, seg.length() - 3) : seg;
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode n : current) {
                JsonNode child = n.get(key);
                if (child == null || child.isNull()) {
                    continue;
                }
                if (wildcard && child.isArray()) {
                    child.forEach(next::add);
                } else {
                    next.add(child);
                }
            }
            current = next;
            if (current.isEmpty()) {
                return ABSENT;
            }
        }
        if (current.size() == 1) {
            return jsonToObject(current.get(0));
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode n : current) {
            values.add(jsonToObject(n));
        }
        return values;
    }

    private Object resolveMember(String path) {
        if (member == null) {
            return ABSENT;
        }
        return switch (path) {
            case "tier" -> member.tier();
            case "status" -> member.status();
            case "segments" -> new ArrayList<Object>(member.segments());
            case "labels" -> new ArrayList<Object>(member.labels());
            case "age" -> member.birthDate() == null ? ABSENT
                    : (double) member.birthDate().until(LocalDate.now(ROME)).getYears();
            case "registeredDaysAgo" -> member.registeredAt() == null ? ABSENT
                    : (double) ChronoUnit.DAYS.between(member.registeredAt(), action.time());
            default -> {
                if (path.startsWith("attributes.") && member.attributes() != null) {
                    yield jsonToObject(member.attributes().get(path.substring("attributes.".length())));
                }
                yield ABSENT;
            }
        };
    }

    private Object resolveContext(String path) {
        ZonedDateTime t = action.time().atZone(ROME);
        return switch (path) {
            case "source" -> action.source();
            case "dayOfWeek" -> t.getDayOfWeek().name().substring(0, 3); // MON..SUN
            case "hour" -> (double) t.getHour();
            case "date" -> t.toLocalDate().toString();
            default -> ABSENT;
        };
    }

    private Object resolveHistory(String path) {
        return switch (path) {
            case "actionCount" -> (double) counters.historyActionCount(action.memberId(), action.type());
            case "daysSinceLastAction" -> {
                long d = counters.historyDaysSinceLastAction(action.memberId(), action.type());
                yield d < 0 ? ABSENT : (double) d;
            }
            default -> ABSENT;
        };
    }

    // ---------- utilità ----------

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object jsonToObject(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return ABSENT;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(e -> list.add(jsonToObject(e)));
            return list;
        }
        return n.asString("");
    }
}
