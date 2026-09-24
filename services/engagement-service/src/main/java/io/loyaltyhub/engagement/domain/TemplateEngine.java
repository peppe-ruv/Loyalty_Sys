package io.loyaltyhub.engagement.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motore dei template dei messaggi (docs/servizi/engagement-service.md §5, docs/03 §9; F-MSG-02). Sostituisce
 * {@code {{percorso}}} con il valore letto dal contesto {@code {data, member, event}}; un percorso assente (o che non
 * porta a un valore semplice) diventa stringa vuota con un log {@code WARN}. Nessuna logica nei template: solo i
 * formattatori {@code |number} (raggruppamento italiano, {@code 1.500}; decimali con la virgola) e {@code |date}
 * (giorno in {@code Europe/Rome}, {@code 31 ottobre 2026}). Puro e senza stato: si usa anche dall'anteprima di BO-19.
 */
// SPEC-GAP: Q-66 — docs/10 §7 cita segnaposto senza radice ({{amount}}, {{campaignName}}); vale la scheda servizio
// (§5, fonte di rango più alto): percorsi su {data, member, event}, quindi {{data.amount}}. campaignName non esiste in
// wallet.points.earned: i template del seed non lo usano.
public final class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);

    /** {@code {{ percorso }}} o {@code {{ percorso | formattatore }}}; spazi ammessi. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}|]*?)\\s*(?:\\|\\s*([^{}|]*?)\\s*)?}}");
    /** Radici ammesse del contesto. */
    public static final Set<String> ROOTS = Set.of("data", "member", "event");
    public static final Set<String> FORMATTERS = Set.of("number", "date");

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Locale IT = Locale.ITALY;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", IT);

    private TemplateEngine() {
    }

    /** Esito di un rendering: testo e percorsi non risolti (per l'anteprima di BO-19). */
    public record Rendered(String text, List<String> missing) {
    }

    /** Rende {@code template} sul contesto; {@code null} → stringa vuota. */
    public static Rendered render(String template, JsonNode context) {
        if (template == null || template.isEmpty()) {
            return new Rendered("", List.of());
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        Set<String> missing = new LinkedHashSet<>();
        while (m.find()) {
            String path = m.group(1);
            String formatter = m.group(2);
            JsonNode value = resolve(context, path);
            String text;
            if (value == null) {
                missing.add(path);
                log.warn("Segnaposto non risolto nel template: {{{}}}", path);
                text = "";
            } else {
                text = format(value, formatter, path);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return new Rendered(out.toString(), List.copyOf(missing));
    }

    /** Solo il testo (percorsi assenti già registrati a WARN). */
    public static String renderText(String template, JsonNode context) {
        return render(template, context).text();
    }

    /**
     * Problemi di sintassi di un template (per la validazione della gestione): segnaposto vuoto, radice diversa da
     * {@code data/member/event}, formattatore sconosciuto, graffe spaiate. Vuoto = valido.
     */
    public static List<String> problems(String template) {
        List<String> problems = new ArrayList<>();
        if (template == null) {
            return problems;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String path = m.group(1);
            String formatter = m.group(2);
            if (path.isBlank()) {
                problems.add("segnaposto vuoto");
                continue;
            }
            String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
            if (!ROOTS.contains(root)) {
                problems.add("segnaposto {{" + path + "}}: il percorso inizia con data., member. o event.");
            }
            if (formatter != null && !FORMATTERS.contains(formatter)) {
                problems.add("formattatore sconosciuto |" + formatter + " (ammessi: number, date)");
            }
        }
        String rest = PLACEHOLDER.matcher(template).replaceAll("");
        if (rest.contains("{{") || rest.contains("}}")) {
            problems.add("graffe {{ }} non bilanciate");
        }
        return problems;
    }

    /** Valore semplice al percorso {@code a.b.c} (anche indici numerici sugli array); {@code null} se assente. */
    static JsonNode resolve(JsonNode context, String path) {
        if (context == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode node = context;
        for (String seg : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            if (node.isArray() && seg.matches("\\d+")) {
                node = node.get(Integer.parseInt(seg));
            } else if (node.isObject()) {
                node = node.get(seg);
            } else {
                return null;
            }
        }
        if (node == null || node.isNull() || node.isMissingNode() || node.isObject() || node.isArray()) {
            return null;
        }
        return node;
    }

    private static String format(JsonNode value, String formatter, String path) {
        if (formatter == null || formatter.isEmpty()) {
            return value.asString("");
        }
        return switch (formatter) {
            case "number" -> number(value, path);
            case "date" -> date(value.asString(""), path);
            default -> {
                log.warn("Formattatore sconosciuto |{} su {}: valore non formattato", formatter, path);
                yield value.asString("");
            }
        };
    }

    /** {@code 1500} → {@code 1.500}; {@code 1.25} → {@code 1,25} (al più due decimali). */
    static String number(JsonNode value, String path) {
        BigDecimal n;
        try {
            n = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString("").trim());
        } catch (NumberFormatException e) {
            log.warn("|number su un valore non numerico in {}: {}", path, value.asString(""));
            return value.asString("");
        }
        DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(IT));
        return f.format(n);
    }

    /** Istante ISO o data {@code yyyy-MM-dd} → {@code 31 ottobre 2026} (giorno civile a Roma). */
    static String date(String raw, String path) {
        String s = raw == null ? "" : raw.trim();
        try {
            if (s.length() == 10) {
                return LocalDate.parse(s).format(DATE);
            }
            Instant at;
            try {
                at = Instant.parse(s);
            } catch (DateTimeParseException e) {
                at = OffsetDateTime.parse(s).toInstant();
            }
            return at.atZone(ROME).toLocalDate().format(DATE);
        } catch (DateTimeParseException e) {
            log.warn("|date su un valore che non è una data in {}: {}", path, s);
            return s;
        }
    }
}
