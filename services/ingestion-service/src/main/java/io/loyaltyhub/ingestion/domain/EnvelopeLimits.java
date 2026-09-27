package io.loyaltyhub.ingestion.domain;

import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Limiti di forma dell'envelope in ingresso (passo 1 della pipeline, docs/servizi/ingestion-service.md §5): lunghezza
 * massima degli attributi e nessun carattere NUL, né negli attributi né in {@code data} (chiavi e valori, a qualunque
 * profondità). PostgreSQL non memorizza NUL in {@code text}/{@code jsonb} e l'indice unico {@code (source_code,
 * event_id)} non accetta chiavi oltre ~2,7 KB: senza questi controlli un solo evento farebbe fallire la scrittura con un
 * errore interno invece di un errore di forma. SPEC-GAP: Q-371 — limiti scelti larghi rispetto agli usi reali.
 */
public final class EnvelopeLimits {

    public static final int MAX_ID = 256;
    public static final int MAX_SOURCE = 200;
    public static final int MAX_TYPE = 200;
    public static final int MAX_SUBJECT = 512;
    public static final int MAX_TIME = 64;

    private static final Map<String, Integer> MAX_BY_ATTRIBUTE = Map.of(
            "id", MAX_ID, "source", MAX_SOURCE, "type", MAX_TYPE, "subject", MAX_SUBJECT, "time", MAX_TIME);

    private EnvelopeLimits() {
    }

    /**
     * Problema di un attributo testuale ({@code id, source, type, subject, time}), senza riportarne il valore;
     * {@code null} se va bene (anche se assente: l'obbligatorietà la controlla la pipeline).
     */
    public static String attributeProblem(String name, String value) {
        return textProblem(name, value, MAX_BY_ATTRIBUTE.get(name));
    }

    /**
     * Problema di un campo testuale che finisce in un attributo o in una colonna: NUL o più di {@code max} caratteri
     * ({@code null} = nessun limite di lunghezza). Il messaggio nomina il campo, mai il valore.
     */
    public static String textProblem(String name, String value, Integer max) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('\0') >= 0) {
            return name + " contiene il carattere NUL, non ammesso";
        }
        if (max != null && value.length() > max) {
            return name + " troppo lungo (al massimo " + max + " caratteri)";
        }
        return null;
    }

    /**
     * Testo memorizzabile in una colonna {@code text}: ogni NUL diventa U+FFFD (carattere sostitutivo), così un valore
     * respinto proprio per il NUL resta riconoscibile nel rapporto senza far fallire la scrittura.
     */
    public static String withoutNul(String value) {
        return value == null || value.indexOf('\0') < 0 ? value : value.replace('\0', '\uFFFD');
    }

    /** {@code data} contiene un carattere NUL in una chiave o in un valore testuale, a qualunque profondità? */
    public static boolean containsNul(JsonNode data) {
        if (data == null) {
            return false;
        }
        Deque<JsonNode> todo = new ArrayDeque<>();
        todo.push(data);
        while (!todo.isEmpty()) {
            JsonNode n = todo.pop();
            if (n.isString()) {
                if (n.asString().indexOf('\0') >= 0) {
                    return true;
                }
            } else if (n.isObject()) {
                for (Map.Entry<String, JsonNode> e : n.properties()) {
                    if (e.getKey().indexOf('\0') >= 0) {
                        return true;
                    }
                    todo.push(e.getValue());
                }
            } else if (n.isArray()) {
                n.forEach(todo::push);
            }
        }
        return false;
    }
}
