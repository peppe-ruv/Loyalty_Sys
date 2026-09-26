package io.loyaltyhub.engagement.messaging;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Lettura di {@code member.registered} / {@code member.updated} in entrambe le versioni (ADR-032, docs/18 §3.4,
 * docs/05 §9–§10): la doppia lettura {@code :1}/{@code :2} dura fino a M10 (Q-346).
 * <ul>
 *   <li>{@code :1} ({@code dataschema} che termina con {@code :1}, oppure assente come nei produttori di Fase 1):
 *       porta ancora {@code firstName}, che resta la fonte del segnaposto {@code {{member.firstName}}};</li>
 *   <li>{@code :2} (e successive): senza dati identificativi; {@code firstName} non si legge nemmeno se comparisse.
 *       {@code locale}, {@code birthYear}, {@code province}, {@code emailHash} non hanno un lettore in engagement e
 *       non si conservano (regola 2: nessun dato senza lettore).</li>
 * </ul>
 * Un campo assente vale {@code null} e non sovrascrive mai il valore già noto nello snapshot (vedi
 * {@code MemberSnapshotRepository#upsertProfile}). Pura e tollerante: un payload inatteso non lancia eccezioni.
 */
public record MemberProfileFact(int version, String firstName, String status, Instant registeredAt) {

    /** Versione dello schema dal suffisso {@code :<n>} del {@code dataschema}; 1 se assente o non leggibile. */
    public static int schemaVersion(String dataschema) {
        if (dataschema == null) {
            return 1;
        }
        int colon = dataschema.lastIndexOf(':');
        if (colon < 0 || colon == dataschema.length() - 1) {
            return 1;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(dataschema.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return 1;
        }
        if (parsed < 1) {
            return 1;
        }
        return parsed;
    }

    /** Legge il payload {@code data} secondo la versione indicata da {@code dataschema}. */
    public static MemberProfileFact parse(String dataschema, JsonNode data) {
        int version = schemaVersion(dataschema);
        String firstName = null;
        // SPEC-GAP: Q-346 — una versione >2 non è ancora definita: la si tratta come :2 (nessun dato personale letto).
        if (version == 1) {
            firstName = text(data, "firstName");
        }
        return new MemberProfileFact(version, firstName, text(data, "status"), instant(text(data, "registeredAt")));
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Stringa non vuota del campo, altrimenti {@code null} (campo assente, nullo, non testuale o vuoto). */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        String s = value.asString();
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }
}
