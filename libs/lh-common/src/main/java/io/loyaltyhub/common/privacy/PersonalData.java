package io.loyaltyhub.common.privacy;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anonimizzazione di un membro (F-MBR-05, docs/03 §2, M7.5): regole condivise da tutti i servizi che tengono dati
 * personali in uno snapshot o in un payload conservato. Pura, senza accesso a DB.
 * <ul>
 *   <li>{@link #isAnonymization}: il fatto di member che porta un membro in {@code ANONYMIZED}
 *       ({@code member.status.changed} con {@code newStatus}, oppure {@code member.registered/updated} con {@code status});</li>
 *   <li>{@link #redact}: copia di un JSON senza le chiavi personali ({@link #KEYS}), a ogni profondità;</li>
 *   <li>{@link #scrub}: sostituisce nel testo libero i valori personali già noti al servizio (nome, e-mail…) con
 *       {@link #PLACEHOLDER}, solo come parole intere e in forma NFC ({@link PersonalTextScrubber}, Q-404);</li>
 *   <li>{@link #redactAndScrub}, {@link #scrubAll}: lo stesso sui valori di un JSON, saltando i valori sicuri
 *       (identificativi, istanti, envelope, stati e codici: {@link PersonalTextScrubber#isSafe}).</li>
 * </ul>
 */
public final class PersonalData {

    /** Stato irreversibile del membro (docs/03 §2). */
    public static final String ANONYMIZED = "ANONYMIZED";

    /** Segnaposto del nome di un membro anonimizzato (docs/03 §2, docs/08 BO-03). */
    public static final String PLACEHOLDER = "Membro anonimo";

    /**
     * Chiavi che contengono dati personali negli snapshot, nei payload degli eventi e nelle voci di audit.
     * SPEC-GAP: Q-122 — né docs/03 né le definizioni degli attributi dicono quali campi sono personali: scelta
     * conservativa, tutto ciò che identifica o descrive la persona (anagrafica, recapiti, indirizzo di spedizione,
     * attributi personalizzati, identificativo esterno); restano id, stato, livello, etichette, segmenti e importi.
     */
    public static final Set<String> KEYS = Set.of(
            "firstName", "lastName", "fullName", "nickname", "email", "emailLower", "phone", "mobile",
            "birthDate", "gender", "city", "address", "street", "zip", "postalCode", "externalId",
            "shipping", "attributes", "consents", "avatarSeed");

    /**
     * Pseudonimo dell'e-mail in {@code member.registered/updated:2} (Q-367): non è in {@link #KEYS} (Q-122), ma il suo
     * valore non è mai sicuro per {@link PersonalTextScrubber} e si ripulisce come testo.
     */
    public static final String EMAIL_HASH = "emailHash";

    /**
     * Pseudonimo del legame account↔membro in {@code member.registered/updated} (Q-552, ADR-048): HMAC-SHA256 di
     * {@code (iss, sub)} con {@code LH_SUBJECT_KEY} ({@link io.loyaltyhub.common.identity.SubjectRef}). Stesso regime di
     * {@link #EMAIL_HASH}: non è in {@link #KEYS} (non si toglie a ogni profondità: nell'envelope {@code subject} è un
     * altro attributo), il suo valore non è mai sicuro per {@link PersonalTextScrubber} e chi conserva i fatti lo toglie
     * esplicitamente all'anonimizzazione (insight).
     */
    public static final String SUBJECT_REF = "subjectRef";

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
     * Valori personali da cercare nel testo libero: i valori non vuoti di {@code values}. Scarta i valori troppo corti
     * e quelli che sono parole del segnaposto («Membro», «anonimo», «Membro anonimo», senza distinguere maiuscole: il
     * segnaposto non cresce a ogni anonimizzazione, Q-404); ordinati dal più lungo (il nome completo si sostituisce
     * prima delle sue parti).
     */
    public static List<String> tokens(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (notBlank(v) && v.trim().length() >= MIN_TOKEN
                    && PersonalTextScrubber.found(PLACEHOLDER, List.of(v.trim())).isEmpty()) {
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

    /**
     * Sostituisce con {@link #PLACEHOLDER} ogni valore di {@code tokens} presente in {@code text} come parola intera,
     * senza distinguere maiuscole e in forma NFC: «Ada» non tocca «Adamo», «Anon» non tocca {@code ANONYMIZED}
     * (Q-404, {@link PersonalTextScrubber#scrub}). Valori nulli o vuoti si saltano; {@code null} resta {@code null}.
     */
    public static String scrub(String text, Collection<String> tokens) {
        return PersonalTextScrubber.scrub(text, tokens);
    }

    /**
     * {@link #redact} più {@link #scrub} su ogni stringa rimasta che non è un valore sicuro (identificativi, istanti,
     * envelope, stati e codici nei campi di codice: {@link PersonalTextScrubber#isSafe}). Righe del membro.
     */
    public static JsonNode redactAndScrub(JsonNode node, Collection<String> tokens) {
        return PersonalTextScrubber.redactAndScrub(node, tokens);
    }

    /** Come {@link #redactAndScrub} senza togliere chiavi (righe di altri membri o entità). */
    public static JsonNode scrubAll(JsonNode node, Collection<String> tokens) {
        return PersonalTextScrubber.scrubAll(node, tokens);
    }

    /** {@code true} se {@code text} contiene uno dei valori come parola intera (senza distinguere maiuscole, NFC). */
    public static boolean containsAny(String text, Collection<String> tokens) {
        return !PersonalTextScrubber.found(text, tokens).isEmpty();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
