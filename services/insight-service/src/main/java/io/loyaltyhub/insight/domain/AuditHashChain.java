package io.loyaltyhub.insight.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Forma canonica e hash della catena di audit, versione 1 (F2-GRC-07, ADR-044, docs/18 §3.15 punto 5; definizione
 * normativa in docs/servizi/insight-service.md §5). È l'implementazione <em>indipendente</em> usata dalla verifica: il
 * database calcola gli stessi hash all'inserimento (migrazione V6, funzioni {@code audit_content_hash} e
 * {@code audit_entry_hash}); i test d'integrazione confrontano le due implementazioni.
 * <ul>
 *   <li>Ogni campo è una netstring {@code <byte UTF-8 in decimale>:<valore>,}; {@code null} è il solo carattere
 *       {@code ~} (una netstring comincia sempre con una cifra, quindi NULL, stringa vuota e confini dei campi non si
 *       confondono).</li>
 *   <li>{@code content = ns("lh.audit.content.v1") ns(summary) ns(before) ns(after)}, con {@code before}/{@code after}
 *       nella resa testuale di jsonb.</li>
 *   <li>{@code entry = ns("lh.audit.entry.v1") ns(service) ns(seq) ns(prev_hash) ns(id) ns(event_id) ns(at)
 *       ns(actor_role) ns(actor_name) ns(entity_type) ns(entity_id) ns(action) ns(correlation_id) ns(content_hash)};
 *       {@code at} in UTC con sei cifre di microsecondi ({@code 2026-09-28T10:15:30.000000Z}), {@code seq} in
 *       decimale.</li>
 *   <li>Hash = SHA-256 dei byte UTF-8 della forma, in esadecimale minuscolo. La prima voce di una catena ha
 *       {@code prev_hash} = {@link #GENESIS}.</li>
 * </ul>
 * Il contenuto (sintesi e diff) entra con il proprio hash: l'anonimizzazione può riscriverlo senza spezzare la catena.
 */
public final class AuditHashChain {

    /** {@code prev_hash} della prima voce di ogni catena: 64 zeri. */
    public static final String GENESIS = "0".repeat(64);

    static final String CONTENT_TAG = "lh.audit.content.v1";
    static final String ENTRY_TAG = "lh.audit.entry.v1";

    private static final String NULL_FIELD = "~";
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private AuditHashChain() {
    }

    /** Hash del contenuto di una voce (sintesi, stato prima, stato dopo). */
    public static String contentHash(String summary, String beforeJson, String afterJson) {
        return sha256Hex(canonicalContent(summary, beforeJson, afterJson));
    }

    /** Hash della voce, calcolato sui campi e sul {@code contentHash} <em>memorizzati</em> nell'anello. */
    public static String entryHash(AuditChainLink link) {
        return sha256Hex(canonicalEntry(link));
    }

    static String canonicalContent(String summary, String beforeJson, String afterJson) {
        return netstring(CONTENT_TAG) + netstring(summary) + netstring(beforeJson) + netstring(afterJson);
    }

    static String canonicalEntry(AuditChainLink l) {
        return netstring(ENTRY_TAG) + netstring(l.service()) + netstring(Long.toString(l.seq()))
                + netstring(l.prevHash()) + netstring(l.id()) + netstring(l.eventId()) + netstring(formatAt(l.at()))
                + netstring(l.actorRole()) + netstring(l.actorName()) + netstring(l.entityType())
                + netstring(l.entityId()) + netstring(l.action()) + netstring(l.correlationId())
                + netstring(l.contentHash());
    }

    /** Netstring di un campo; {@code null} → {@code ~}. */
    static String netstring(String value) {
        if (value == null) {
            return NULL_FIELD;
        }
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value + ",";
    }

    /** Istante in UTC con microsecondi (la precisione di {@code timestamptz}); {@code null} resta {@code null}. */
    static String formatAt(Instant at) {
        return at == null ? null : AT.format(at);
    }

    static String sha256Hex(String material) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(sha.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile nella JVM", e);
        }
    }
}
