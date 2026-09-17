package io.loyaltyhub.ingressadapters.schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Schema di un tipo azione (RF-98): identificatore di sistema,
 * nome, attributi tipizzati (BOOLEAN, DATETIME, NUMBER, TEXT, LIST) con obbligatorietà, stato attivo, versione.
 * Il catalogo dei tipi azione del backoffice (RF-01) è fatto di schemi: un'azione con tipo sconosciuto o attributi
 * non validi va nella coda di scarto con motivo (RF-45), a meno che lo schema sia in modalità {@code lenient}.
 */
public record EventSchema(String actionType, String name, int version, boolean active, boolean lenient, List<Attribute> attributes) {
    public enum Type { BOOLEAN, DATETIME, NUMBER, TEXT, LIST }
    public record Attribute(String name, Type type, boolean required, String description) {}

    /** Errori di validazione (vuoto = valido). */
    public List<String> validate(Map<String, Object> attrs) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> a = attrs == null ? Map.of() : attrs;
        if (attributes == null) return errors;
        for (Attribute at : attributes) {
            Object v = a.get(at.name());
            if (v == null) { if (at.required()) errors.add("missing " + at.name()); continue; }
            boolean ok = switch (at.type()) {
                case BOOLEAN -> v instanceof Boolean || "true".equalsIgnoreCase(v.toString()) || "false".equalsIgnoreCase(v.toString());
                case NUMBER -> v instanceof Number || v.toString().matches("-?\\d+(\\.\\d+)?");
                case DATETIME -> { try { Instant.parse(v.toString()); yield true; } catch (Exception e) { yield false; } }
                case TEXT -> !(v instanceof Map) && !(v instanceof List);
                case LIST -> v instanceof List;
            };
            if (!ok) errors.add("attribute " + at.name() + " is not " + at.type());
        }
        if (!lenient) for (String k : a.keySet()) if (attributes.stream().noneMatch(x -> x.name().equals(k)) && !k.equals("channel") && !k.equals("lines") && !k.equals("labels")) errors.add("unknown attribute " + k);
        return errors;
    }
}
