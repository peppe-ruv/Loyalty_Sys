package io.loyaltyhub.common.privacy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulizia dei valori personali noti di un membro anonimizzato nei testi e nei payload conservati da ogni servizio
 * (F-MBR-05, M7.5), senza mai corrompere valori strutturali (M8.12a, revisioni P9 e P18; comune a tutti i servizi da
 * Q-404: {@link PersonalData#scrub}, {@link PersonalData#redactAndScrub} e {@link PersonalData#scrubAll} la usano).
 * <ul>
 *   <li><strong>Solo parole intere</strong>: un valore si sostituisce con «Membro anonimo» solo se non è attaccato a
 *       lettere, segni diacritici o cifre (il trattino basso separa: «Ottavio_Q» si ripulisce). Testo e valori si
 *       confrontano in forma NFC e senza distinguere maiuscole. Un soprannome «Anon» o un nome «Zed» non toccano
 *       «ANONYMIZED» o «Zedda».</li>
 *   <li><strong>Si salta solo ciò che è sicuro</strong>: i campi che portano identificativi, istanti e l'envelope
 *       ({@link #isFixed}) non si toccano mai; i campi di enumerazione e codice (stati, tipi, codici, azioni:
 *       {@link #isCoded}) si conservano solo se il valore ha la forma di un codice ({@code ACTIVE},
 *       {@code member.status.changed}); ogni altro campo è testo libero e si ripulisce, compresi {@code reason} e
 *       {@code subject} ({@code email:<indirizzo>}, {@code external:<id>}). Nel testo libero resta intatto solo un
 *       valore che è per intero una costante in maiuscolo ({@code TEST}, {@code MEMBER_REQUEST}: i motivi enumerati
 *       di {@code wallet.points.adjusted}), anche se un soprannome ci coincide. Gli identificativi personali ({@code externalId},
 *       {@link PersonalData#KEYS}) e lo pseudonimo {@link PersonalData#EMAIL_HASH} non sono mai sicuri.</li>
 * </ul>
 * Toglie le chiavi personali come {@link PersonalData#redact}. Pura, senza accesso a DB.
 */
public final class PersonalTextScrubber {

    /** Campi mai riscritti: identificativi e istanti dell'envelope CloudEvents e dell'audit. */
    private static final Set<String> FIXED = Set.of(
            "id", "specversion", "datacontenttype", "dataschema", "time", "type", "source", "service", "entityType",
            "contentHash");

    /** Suffissi dei campi mai riscritti: {@code memberId}, {@code createdAt}. */
    private static final List<String> FIXED_SUFFIXES = List.of("Id", "At");

    /** Campi di enumerazione e codice: il valore si conserva se ha la forma di un codice. */
    private static final Set<String> CODED = Set.of(
            "status", "kind", "action", "code", "currency", "channel", "tier", "level", "role");

    /** Suffissi dei campi di enumerazione e codice: {@code newStatus}, {@code eventType}, {@code rewardCode}. */
    private static final List<String> CODED_SUFFIXES = List.of("Status", "Type", "Code");

    /** Forma di un codice: una sola parola di lettere, cifre, punti, trattini, due punti e trattini bassi. */
    private static final Pattern CODE_VALUE = Pattern.compile("[\\p{L}\\p{N}_.:-]+");

    /**
     * Valore che è per intero una costante in maiuscolo ({@code TEST}, {@code MEMBER_REQUEST}): conservato ovunque. Serve
     * almeno una lettera: un valore di sole cifre (un telefono, un id esterno numerico) non è una costante.
     */
    private static final Pattern CONSTANT = Pattern.compile("(?=[A-Z0-9_]*[A-Z])[A-Z0-9]+(?:_[A-Z0-9]+)*");

    /** Carattere di parola: lettera, segno diacritico o cifra. */
    private static final String WORD = "[\\p{L}\\p{M}\\p{N}]";

    private PersonalTextScrubber() {
    }

    /** Il valore di questo campo non si riscrive mai (identificativi, istanti, envelope). */
    public static boolean isFixed(String key) {
        if (key == null || personal(key)) {
            return false;
        }
        return FIXED.contains(key) || key.startsWith("lh") || hasSuffix(key, FIXED_SUFFIXES);
    }

    /** Campo di enumerazione o codice: il valore si conserva se ha la forma di un codice. */
    public static boolean isCoded(String key) {
        return key != null && !personal(key) && (CODED.contains(key) || hasSuffix(key, CODED_SUFFIXES));
    }

    /** Il valore {@code value} del campo {@code key} resta com'è (identificativo, istante, codice, costante). */
    public static boolean isSafe(String key, String value) {
        if (isFixed(key)) {
            return true;
        }
        if (value == null || personal(key)) {
            return false;
        }
        return isCoded(key) ? CODE_VALUE.matcher(value).matches() : CONSTANT.matcher(value).matches();
    }

    /** Sostituisce, senza distinguere maiuscole e solo come parola intera, ogni valore con «Membro anonimo». */
    public static String scrub(String text, Collection<String> tokens) {
        if (text == null || tokens == null || tokens.isEmpty()) {
            return text;
        }
        String out = nfc(text);
        boolean changed = false;
        for (String t : tokens) {
            if (t == null || t.isBlank()) {
                continue;
            }
            Matcher m = word(t).matcher(out);
            if (m.find()) {
                out = m.replaceAll(Matcher.quoteReplacement(PersonalData.PLACEHOLDER));
                changed = true;
            }
        }
        return changed ? out : text;
    }

    /**
     * I valori di {@code tokens} presenti come parole intere in {@code text}, così come vi compaiono (forma NFC): la
     * prova, per {@code audit_redact}, che la voce riguarda il membro.
     */
    public static Set<String> found(String text, Collection<String> tokens) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || tokens == null) {
            return out;
        }
        String normalized = nfc(text);
        for (String t : tokens) {
            if (t == null || t.isBlank()) {
                continue;
            }
            Matcher m = word(t).matcher(normalized);
            while (m.find()) {
                out.add(m.group());
            }
        }
        return out;
    }

    /** Come {@link #found(String, Collection)} su tutti i valori testuali di un documento JSON. */
    public static Set<String> found(JsonNode node, Collection<String> tokens) {
        Set<String> out = new LinkedHashSet<>();
        collect(node, tokens, out);
        return out;
    }

    /** Copia senza le chiavi personali e con {@link #scrub} sui valori non sicuri (righe del membro). */
    public static JsonNode redactAndScrub(JsonNode node, Collection<String> tokens) {
        JsonNode copy = PersonalData.redact(node);
        return copy == null ? null : scrubStrings(copy, null, tokens);
    }

    /** Copia con {@link #scrub} sui valori non sicuri, senza togliere chiavi (righe di altre entità). */
    public static JsonNode scrubAll(JsonNode node, Collection<String> tokens) {
        return node == null ? null : scrubStrings(node.deepCopy(), null, tokens);
    }

    private static JsonNode scrubStrings(JsonNode node, String key, Collection<String> tokens) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.properties().forEach(e -> names.add(e.getKey()));
            for (String name : names) {
                obj.set(name, scrubStrings(obj.get(name), name, tokens));
            }
            return obj;
        }
        if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, scrubStrings(arr.get(i), key, tokens));
            }
            return arr;
        }
        if (node != null && node.isString() && !isSafe(key, node.asString())) {
            String s = node.asString();
            String scrubbed = scrub(s, tokens);
            return scrubbed.equals(s) ? node : StringNode.valueOf(scrubbed);
        }
        return node;
    }

    private static void collect(JsonNode node, Collection<String> tokens, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            out.addAll(found(node.asString(), tokens));
        } else if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                collect(child, tokens, out);
            }
        }
    }

    private static Pattern word(String token) {
        return Pattern.compile("(?<!" + WORD + ")" + Pattern.quote(nfc(token.trim())) + "(?!" + WORD + ")",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String nfc(String s) {
        return Normalizer.isNormalized(s, Normalizer.Form.NFC) ? s : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    private static boolean personal(String key) {
        return PersonalData.KEYS.contains(key) || PersonalData.EMAIL_HASH.equals(key);
    }

    private static boolean hasSuffix(String key, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (key.length() > suffix.length() && key.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
