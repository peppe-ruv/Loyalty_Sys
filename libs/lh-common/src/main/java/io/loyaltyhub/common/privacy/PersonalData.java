package io.loyaltyhub.common.privacy;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonimizzazione di un membro (F-MBR-05, docs/03 §2, M7.5): regole condivise da tutti i servizi che tengono dati
 * personali in uno snapshot o in un payload conservato. Pura, senza accesso a DB.
 * <ul>
 *   <li>{@link #isAnonymization}: il fatto di member che porta un membro in {@code ANONYMIZED}
 *       ({@code member.status.changed} con {@code newStatus}, oppure {@code member.registered/updated} con {@code status});</li>
 *   <li>{@link #redact}: copia di un JSON senza le chiavi personali ({@link #KEYS}), a ogni profondità;</li>
 *   <li>{@link #scrub}: sostituisce nel testo libero i valori personali già noti al servizio (nome, e-mail…) con
 *       {@link #PLACEHOLDER}.</li>
 * </ul>
 */
public final class PersonalData {

    /** Stato irreversibile del membro (docs/03 §2). */
    public static final String ANONYMIZED = "ANONYMIZED";

    /** Segnaposto del nome di un membro anonimizzato (docs/03 §2, docs/08 BO-03). */
    public static final String PLACEHOLDER = "Membro anonimo";

    /**
     * Chiavi che contengono dati personali negli snapshot, nei payload degli eventi e nelle voci di audit.
     * SPEC-GAP: Q-B3 — né docs/03 né le definizioni degli attributi dicono quali campi sono personali: scelta
     * conservativa, tutto ciò che identifica o descrive la persona (anagrafica, recapiti, indirizzo di spedizione,
     * attributi personalizzati, identificativo esterno); restano id, stato, livello, etichette, segmenti e importi.
     */
    public static final Set<String> KEYS = Set.of(
            "firstName", "lastName", "fullName", "nickname", "email", "emailLower", "phone", "mobile",
            "birthDate", "gender", "city", "address", "street", "zip", "postalCode", "externalId",
            "shipping", "attributes", "consents", "avatarSeed");

    /** Lunghezza minima di un valore da cercare nel testo libero (evita di cancellare sillabe comuni). */
    static final int MIN_TOKEN = 3;

    private PersonalData() {
    }

    /** {@code true} se il fatto porta il membro nello stato {@code ANONYMIZED}. */
    public static boolean isAnonymization(LhEvent<JsonNode> event) {
        if (event == null || event.type() == null || event.data() == null) {
            return false;
        }
        JsonNode d = event.data();
        return switch (event.type()) {
            case LhEventTypes.Fact.MEMBER_STATUS_CHANGED -> ANONYMIZED.equals(d.path("newStatus").asString(""));
            case LhEventTypes.Fact.MEMBER_UPDATED, LhEventTypes.Fact.MEMBER_REGISTERED ->
                    ANONYMIZED.equals(d.path("status").asString(""));
            default -> false;
        };
    }

    /** Copia di {@code node} senza le chiavi personali, a ogni livello (oggetti e array). {@code null} resta {@code null}. */
    public static JsonNode redact(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode copy = node.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> drop = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                if (KEYS.contains(e.getKey())) {
                    drop.add(e.getKey());
                } else {
                    strip(e.getValue());
                }
            }
            drop.forEach(obj::remove);
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(PersonalData::strip);
        }
    }

    /**
     * Valori personali da cercare nel testo libero: i valori non vuoti di {@code values}. Scarta i segnaposto e i
     * valori troppo corti; ordinati dal più lungo (il nome completo si sostituisce prima delle sue parti).
     */
    public static List<String> tokens(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (notBlank(v) && v.trim().length() >= MIN_TOKEN && !PLACEHOLDER.equalsIgnoreCase(v.trim())) {
                out.add(v.trim());
            }
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    /** Come {@link #tokens(Collection)}, più {@code "nome cognome"} quando ci sono entrambi. */
    public static List<String> nameTokens(String firstName, String lastName, String... others) {
        List<String> values = new ArrayList<>();
        if (notBlank(firstName) && notBlank(lastName)) {
            values.add(firstName.trim() + " " + lastName.trim());
        }
        values.add(firstName);
        values.add(lastName);
        values.addAll(java.util.Arrays.asList(others));
        return tokens(values);
    }

    /** Sostituisce (senza distinguere maiuscole) ogni valore di {@code tokens} con {@link #PLACEHOLDER}. */
    public static String scrub(String text, Collection<String> tokens) {
        if (text == null || tokens == null || tokens.isEmpty()) {
            return text;
        }
        String out = text;
        for (String t : tokens) {
            if (t == null || t.isBlank()) {
                continue;
            }
            Matcher m = Pattern.compile(Pattern.quote(t), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(out);
            out = m.replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
        }
        return out;
    }

    /** {@link #redact} più {@link #scrub} su ogni stringa rimasta (chiavi comprese nei valori, non nei nomi). */
    public static JsonNode redactAndScrub(JsonNode node, Collection<String> tokens) {
        JsonNode copy = redact(node);
        return copy == null ? null : scrubStrings(copy, tokens);
    }

    /** Copia di {@code node} con {@link #scrub} su ogni stringa, senza togliere chiavi (righe di altri membri o entità). */
    public static JsonNode scrubAll(JsonNode node, Collection<String> tokens) {
        return node == null ? null : scrubStrings(node.deepCopy(), tokens);
    }

    private static JsonNode scrubStrings(JsonNode node, Collection<String> tokens) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.properties().forEach(e -> names.add(e.getKey()));
            for (String name : names) {
                obj.set(name, scrubStrings(obj.get(name), tokens));
            }
            return obj;
        }
        if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, scrubStrings(arr.get(i), tokens));
            }
            return arr;
        }
        if (node != null && node.isString()) {
            String s = node.asString();
            String scrubbed = scrub(s, tokens);
            return scrubbed.equals(s) ? node : StringNode.valueOf(scrubbed);
        }
        return node;
    }

    /** {@code true} se {@code text} contiene uno dei valori (senza distinguere maiuscole). */
    public static boolean containsAny(String text, Collection<String> tokens) {
        if (text == null || tokens == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String t : tokens) {
            if (t != null && !t.isBlank() && lower.contains(t.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
