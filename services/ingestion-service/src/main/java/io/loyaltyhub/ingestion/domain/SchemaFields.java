package io.loyaltyhub.ingestion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Elenco piatto dei campi {@code data.*} dedotti dal JSON Schema di un tipo azione
 * ({@code GET /v1/event-types/{code}/fields}, docs/servizi/ingestion-service.md §3): alimenta il costruttore di
 * condizioni di BO-06. Gli oggetti annidati si appiattiscono ({@code data.address.city}); gli array di oggetti usano
 * {@code [*]} ({@code data.items[*].category}, vero se almeno un elemento soddisfa: docs/03 §3.3).
 */
public final class SchemaFields {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(String path, String type, boolean required,
                        @JsonProperty("enum") List<String> enumValues, String format) {
    }

    private SchemaFields() {
    }

    public static List<Field> of(JsonNode schema) {
        List<Field> out = new ArrayList<>();
        if (schema != null && schema.isObject()) {
            walk(schema, "data", true, out);
        }
        return out;
    }

    private static void walk(JsonNode schema, String prefix, boolean parentRequired, List<Field> out) {
        JsonNode props = schema.get("properties");
        if (props == null || !props.isObject()) {
            return;
        }
        Set<String> required = new HashSet<>();
        JsonNode req = schema.get("required");
        if (req != null && req.isArray()) {
            req.forEach(r -> required.add(r.asString()));
        }
        for (var e : props.properties()) {
            String path = prefix + "." + e.getKey();
            JsonNode child = e.getValue();
            boolean isRequired = parentRequired && required.contains(e.getKey());
            String type = typeOf(child);
            if ("object".equals(type) && child.has("properties")) {
                walk(child, path, isRequired, out);
                continue;
            }
            if ("array".equals(type)) {
                JsonNode items = child.get("items");
                if (items != null && "object".equals(typeOf(items)) && items.has("properties")) {
                    walk(items, path + "[*]", false, out);
                    continue;
                }
                // Array di valori semplici: un unico campo (contains / ncontains).
                out.add(new Field(path, "array", isRequired, enumOf(items), null));
                continue;
            }
            out.add(new Field(path, type, isRequired, enumOf(child), textOrNull(child.get("format"))));
        }
    }

    private static String typeOf(JsonNode schema) {
        if (schema == null) {
            return "string";
        }
        JsonNode t = schema.get("type");
        if (t != null && t.isString()) {
            return t.asString();
        }
        if (t != null && t.isArray()) {
            for (JsonNode x : t) {
                if (!"null".equals(x.asString())) {
                    return x.asString();
                }
            }
        }
        if (schema.has("enum")) {
            return "string";
        }
        return schema.has("properties") ? "object" : "string";
    }

    private static List<String> enumOf(JsonNode schema) {
        JsonNode en = schema == null ? null : schema.get("enum");
        if (en == null || !en.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        en.forEach(v -> values.add(v.asString()));
        return values;
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }
}
