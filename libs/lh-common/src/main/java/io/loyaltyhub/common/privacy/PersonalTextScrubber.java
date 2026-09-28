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
 *       «ANONYMIZED» o «Zedda». Le scritture senza spazi (han, hiragana, katakana, thai, lao, khmer, birmano) non
 *       fanno da confine: «王伟先» si ripulisce in «王伟先生», «Ada» in «Adaさん». Un valore di sole cifre lungo almeno
 *       8 (un telefono) si riconosce anche con un prefisso internazionale attaccato: da 1 a 3 cifre, con {@code +} o
 *       {@code 00} facoltativi davanti ({@code +39…}, {@code 0039…}, {@code 39…}, {@code 123…}).</li>
 *   <li><strong>Si salta solo ciò che è sicuro</strong>: i campi che portano identificativi, istanti e l'envelope
 *       ({@link #isFixed}) non si riscrivono; i campi di enumerazione e codice (stati, tipi, codici, azioni:
 *       {@link #isCoded}) si conservano solo se il valore ha la forma di un codice ({@code ACTIVE},
 *       {@code member.status.changed}); ogni altro campo è testo libero e si ripulisce, compresi {@code reason} e
 *       {@code subject} ({@code email:<indirizzo>}, {@code external:<id>}). Nel testo libero resta intatto un valore
 *       che è per intero una parola in maiuscolo con almeno una lettera ({@code TEST}, {@code MEMBER_REQUEST},
 *       {@code GOODWILL}: {@link #isSafe}). Gli identificativi personali ({@code externalId}, {@link PersonalData#KEYS})
 *       e lo pseudonimo {@link PersonalData#EMAIL_HASH} non sono mai sicuri.</li>
 *   <li><strong>Un dato del membro per intero non è mai sicuro</strong> (revisione Q-404): un valore saltato come
 *       sicuro che coincide per intero con un valore del membro diventa comunque «Membro anonimo»
 *       ({@code "customerId":"CRM101"}, {@code "lhactor":"<e-mail>"}, {@code "level":"Ada"}). Il confronto non
 *       distingue maiuscole per i valori identificativi (con una cifra o una chiocciola: e-mail, telefono, id esterno,
 *       pseudonimo) e le distingue per i nomi, così un soprannome «Active» o «Test» non tocca {@code ACTIVE} o
 *       {@code TEST}; nei campi di identificativo e di codice un nome non tocca mai un valore in maiuscolo (un
 *       soprannome «ACTIVE» non corrompe uno stato). Dentro un valore sicuro più lungo si ripuliscono, come parole
 *       intere, le e-mail e gli identificativi con lettere e cifre insieme ({@code ORD-CRM101}, {@code web:<e-mail>},
 *       {@code CRM101_2026}); un valore di sole cifre solo per intero, un nome mai. Fanno eccezione solo gli attributi
 *       dell'envelope CloudEvents alla radice ({@code id}, {@code specversion}, {@code type}, {@code source},
 *       {@code time}, {@code datacontenttype}, {@code dataschema}).</li>
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

    /** Attributi dell'envelope CloudEvents alla radice: mai riscritti, neppure se coincidono con un valore del membro. */
    private static final Set<String> ENVELOPE = Set.of(
            "id", "specversion", "datacontenttype", "dataschema", "time", "type", "source");

    /** Forma di un codice: una sola parola di lettere, cifre, punti, trattini, due punti e trattini bassi. */
    private static final Pattern CODE_VALUE = Pattern.compile("[\\p{L}\\p{N}_.:-]+");

    /**
     * Valore che è per intero una parola in maiuscolo ({@code TEST}, {@code MEMBER_REQUEST}): conservato nel testo libero.
     * Serve almeno una lettera: un valore di sole cifre (un telefono, un id esterno numerico) non lo è.
     */
    private static final Pattern CONSTANT = Pattern.compile("(?=[A-Z0-9_]*[A-Z])[A-Z0-9]+(?:_[A-Z0-9]+)*");

    /** Carattere di parola: lettera, segno diacritico o cifra. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]");

    /** Scritture senza spazi tra le parole: non fanno da confine di parola. */
    private static final String NON_SPACED =
            "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}";

    /** Carattere che, attaccato a un valore, lo rende parte di una parola più lunga. */
    private static final String NEIGHBOR = "[\\p{L}\\p{M}\\p{N}&&[^" + NON_SPACED + "]]";

    private static final Pattern NON_SPACED_CHAR = Pattern.compile("[" + NON_SPACED + "]");

    /** Valore di sole cifre abbastanza lungo da essere un telefono: riconosciuto anche con il prefisso internazionale. */
    private static final Pattern PHONE = Pattern.compile("\\d{8,}");

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

    /**
     * Il valore {@code value} del campo {@code key} ha la forma di un valore sicuro (identificativo, istante, codice,
     * costante). Nei documenti JSON vince comunque {@link #isMemberValue}: un dato del membro per intero si sostituisce.
     */
    public static boolean isSafe(String key, String value) {
        if (isFixed(key)) {
            return true;
        }
        if (value == null || personal(key)) {
            return false;
        }
        return isCoded(key) ? CODE_VALUE.matcher(value).matches() : CONSTANT.matcher(value).matches();
    }

    /**
     * {@code value} coincide per intero con un valore del membro: senza distinguere maiuscole per i valori
     * identificativi (con una cifra o una chiocciola; un telefono anche con il prefisso), con le stesse maiuscole per
     * i nomi. Nei campi di identificativo e di codice ({@code key}) un nome non coincide mai con un valore in maiuscolo.
     */
    public static boolean isMemberValue(String key, String value, Collection<String> tokens) {
        if (value == null || tokens == null) {
            return false;
        }
        String v = nfc(value.trim());
        if (v.isEmpty()) {
            return false;
        }
        boolean structural = isFixed(key) || isCoded(key);
        for (String t : tokens) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String n = nfc(t.trim());
            if (identifier(n)) {
                if (word(n).matcher(v).matches()) {
                    return true;
                }
            } else if (v.equals(n) && !(structural && CONSTANT.matcher(v).matches())) {
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
     * prova, per {@code audit_redact}, che la voce riguarda il membro. Un valore trovato attaccato ad altre lettere
     * (scritture senza spazi) o con il prefisso di un telefono si restituisce con la parola intera che lo contiene,
     * così {@code audit_mentions} (V6 di insight, confini {@code [:alnum:]}) lo ritrova.
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
                out.add(enclosingWord(normalized, m.start(), m.end()));
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
        return copy == null ? null : scrubStrings(copy, null, tokens, 0);
    }

    /** Copia con {@link #scrub} sui valori non sicuri, senza togliere chiavi (righe di altre entità). */
    public static JsonNode scrubAll(JsonNode node, Collection<String> tokens) {
        return node == null ? null : scrubStrings(node.deepCopy(), null, tokens, 0);
    }

    private static JsonNode scrubStrings(JsonNode node, String key, Collection<String> tokens, int depth) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.properties().forEach(e -> names.add(e.getKey()));
            for (String name : names) {
                obj.set(name, scrubStrings(obj.get(name), name, tokens, depth + 1));
            }
            return obj;
        }
        if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, scrubStrings(arr.get(i), key, tokens, depth + 1));
            }
            return arr;
        }
        if (node != null && node.isString()) {
            String s = node.asString();
            if (!isSafe(key, s)) {
                String scrubbed = scrub(s, tokens);
                return scrubbed.equals(s) ? node : StringNode.valueOf(scrubbed);
            }
            if (depth == 1 && ENVELOPE.contains(key)) {
                return node;
            }
            if (isMemberValue(key, s, tokens)) {
                return StringNode.valueOf(PersonalData.PLACEHOLDER);
            }
            String scrubbed = scrub(s, embeddable(tokens));
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
        String t = nfc(token.trim());
        String left = nonSpaced(t.codePointAt(0)) ? "" : "(?<!" + NEIGHBOR + ")";
        String right = nonSpaced(t.codePointBefore(t.length())) ? "" : "(?!" + NEIGHBOR + ")";
        String prefix = PHONE.matcher(t).matches() ? "(?:(?:\\+|00)?\\d{1,3})?" : "";
        return Pattern.compile(left + prefix + Pattern.quote(t) + right, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Il tratto {@code [start, end)} esteso alle lettere, ai segni e alle cifre attaccati (la parola che lo contiene). */
    private static String enclosingWord(String text, int start, int end) {
        int s = start;
        while (s > 0 && isWordChar(text.codePointBefore(s))) {
            s -= Character.charCount(text.codePointBefore(s));
        }
        int e = end;
        while (e < text.length() && isWordChar(text.codePointAt(e))) {
            e += Character.charCount(text.codePointAt(e));
        }
        return text.substring(s, e);
    }

    private static boolean isWordChar(int codePoint) {
        return WORD.matcher(Character.toString(codePoint)).matches();
    }

    private static boolean nonSpaced(int codePoint) {
        return NON_SPACED_CHAR.matcher(Character.toString(codePoint)).matches();
    }

    /**
     * Valori riconoscibili anche dentro un valore sicuro più lungo ({@code ORD-CRM101}, {@code web:<e-mail>}): le e-mail
     * e gli identificativi con lettere e cifre insieme. Un valore di sole cifre si confronta solo per intero (non tocca
     * {@code MBR-000123}, {@code RWD-10234} o un UUID), un nome mai.
     */
    private static List<String> embeddable(Collection<String> tokens) {
        List<String> out = new ArrayList<>();
        if (tokens == null) {
            return out;
        }
        for (String t : tokens) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String n = t.trim();
            if (n.indexOf('@') >= 0
                    || (n.chars().anyMatch(Character::isDigit) && n.chars().anyMatch(Character::isLetter))) {
                out.add(t);
            }
        }
        return out;
    }

    /** Valore identificativo (e-mail, telefono, id esterno, pseudonimo): contiene una cifra o una chiocciola. */
    private static boolean identifier(String token) {
        return token.indexOf('@') >= 0 || token.chars().anyMatch(Character::isDigit);
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
