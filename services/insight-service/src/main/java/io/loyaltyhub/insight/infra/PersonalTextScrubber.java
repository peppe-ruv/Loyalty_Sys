package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.privacy.PersonalData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulizia dei valori personali noti di un membro anonimizzato nelle copie di insight (F-MBR-05, M7.5), senza mai
 * corrompere valori strutturali (M8.12a, revisione P9).
 * <ul>
 *   <li><strong>Solo parole intere</strong>: un valore si sostituisce con «Membro anonimo» solo se non è attaccato a
 *       lettere o cifre. Un soprannome «Anon» o un nome «Zed» non toccano più «ANONYMIZED» o «Zedda».</li>
 *   <li><strong>Mai i campi strutturali</strong>: stati, tipi, codici, identificativi, istanti e campi dell'envelope
 *       ({@link #isStructural}) restano come sono, anche se contengono un valore uguale a un soprannome (per esempio
 *       «Active»). Gli identificativi personali ({@code externalId}, {@link PersonalData#KEYS}) non sono strutturali.</li>
 * </ul>
 * Toglie le chiavi personali come {@link PersonalData#redact}. Sostituisce, per insight, {@code PersonalData.scrub},
 * che confronta sottostringhe: la stessa correzione per engagement e ingestion è la domanda Q-404.
 */
public final class PersonalTextScrubber {

    /** Campi strutturali esatti: stati, tipi, codici, azioni e campi dell'envelope CloudEvents. */
    private static final Set<String> STRUCTURAL = Set.of(
            "status", "type", "kind", "action", "code", "reason", "currency", "channel", "tier", "level", "role",
            "id", "source", "subject", "time", "specversion", "datacontenttype", "dataschema", "entityType",
            "service", "contentHash");

    /**
     * Suffissi strutturali: {@code newStatus}, {@code eventType}, {@code rewardCode}, {@code memberId},
     * {@code createdAt}. {@code emailHash} non è strutturale: è uno pseudonimo che si sostituisce (Q-367).
     */
    private static final List<String> STRUCTURAL_SUFFIXES = List.of("Status", "Type", "Code", "Id", "At");

    private PersonalTextScrubber() {
    }

    /** Il valore di questo campo è strutturale e non si riscrive mai. */
    public static boolean isStructural(String key) {
        if (key == null || PersonalData.KEYS.contains(key)) {
            return false;
        }
        if (STRUCTURAL.contains(key) || key.startsWith("lh")) {
            return true;
        }
        for (String suffix : STRUCTURAL_SUFFIXES) {
            if (key.length() > suffix.length() && key.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Sostituisce, senza distinguere maiuscole e solo come parola intera, ogni valore con «Membro anonimo». */
    public static String scrub(String text, Collection<String> tokens) {
        if (text == null || tokens == null || tokens.isEmpty()) {
            return text;
        }
        String out = text;
        for (String t : tokens) {
            if (t == null || t.isBlank()) {
                continue;
            }
            Pattern word = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(t) + "(?![\\p{L}\\p{N}_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            out = word.matcher(out).replaceAll(Matcher.quoteReplacement(PersonalData.PLACEHOLDER));
        }
        return out;
    }

    /** Copia senza le chiavi personali e con {@link #scrub} sui valori non strutturali (righe del membro). */
    public static JsonNode redactAndScrub(JsonNode node, Collection<String> tokens) {
        JsonNode copy = PersonalData.redact(node);
        return copy == null ? null : scrubStrings(copy, null, tokens);
    }

    /** Copia con {@link #scrub} sui valori non strutturali, senza togliere chiavi (righe di altre entità). */
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
        if (node != null && node.isString() && !isStructural(key)) {
            String s = node.asString();
            String scrubbed = scrub(s, tokens);
            return scrubbed.equals(s) ? node : StringNode.valueOf(scrubbed);
        }
        return node;
    }
}
