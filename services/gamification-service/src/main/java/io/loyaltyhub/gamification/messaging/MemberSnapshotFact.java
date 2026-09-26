package io.loyaltyhub.gamification.messaging;

import tools.jackson.databind.JsonNode;

/**
 * Lettura di {@code member.registered} / {@code member.updated} in entrambe le versioni (ADR-032, docs/18 §3.4,
 * Q-346, Q-368), come {@code MemberProfileFact} di engagement e lo snapshot di reward: la doppia lettura
 * {@code :1}/{@code :2} dura fino a M10.
 * <ul>
 *   <li>{@code :1} ({@code dataschema} che termina con {@code :1}, oppure assente come nei produttori di Fase 1):
 *       porta ancora {@code nickname}/{@code firstName}/{@code lastName}, da cui il nome mostrato (docs/03 §8);</li>
 *   <li>{@code :2} (e successive): senza dati identificativi; il nome non si legge nemmeno se comparisse. Da qui in
 *       poi il nome delle classifiche lo risolve il BFF chiedendolo a member-service (Q-368).</li>
 * </ul>
 * Un campo assente vale {@code null} e non sovrascrive mai il valore già noto nello snapshot
 * ({@code MemberSnapshotRepository#upsert}). Pura e tollerante: un payload inatteso non lancia eccezioni.
 */
public record MemberSnapshotFact(int version, String nickname, String status) {

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
        return parsed < 1 ? 1 : parsed;
    }

    /** Legge il payload {@code data} secondo la versione indicata da {@code dataschema}. */
    public static MemberSnapshotFact parse(String dataschema, JsonNode data) {
        int version = schemaVersion(dataschema);
        String nickname = null;
        // SPEC-GAP: Q-346 — una versione >2 non è ancora definita: la si tratta come :2 (nessun dato personale letto).
        if (version == 1 && data != null && data.isObject()) {
            nickname = MemberSnapshotHandler.nickname(data);
        }
        return new MemberSnapshotFact(version, nickname, text(data, "status"));
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
        return s == null || s.isBlank() ? null : s;
    }
}
