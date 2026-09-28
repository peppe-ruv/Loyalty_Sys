package io.loyaltyhub.insight.domain;

import java.time.Instant;

/**
 * Un'ancora della catena di audit: l'hash della voce {@code seq} del servizio, registrato a parte. Nel database
 * ({@code audit_anchor}, sola inserzione, istante sempre fissato dal database): {@code DAILY} = job giornaliero dopo una
 * verifica; {@code PURGE} = ultima voce cancellata, a cui la prima voce rimasta si aggancia, con {@code entryAt} =
 * istante della voce più recente cancellata; {@code BACKFILL} = stato delle voci esistenti alla migrazione V6.
 * {@code EXTERNAL} non è nel database: è un'ancora copiata dai log e passata alla verifica
 * ({@code GET /v1/audit/verify?service=&seq=&hash=}).
 *
 * @param entryAt solo per {@code PURGE}: {@code at} della voce più recente cancellata; altrimenti {@code null}
 */
public record AuditAnchor(String service, long seq, String entryHash, String kind, Instant anchoredAt,
                          Instant entryAt) {

    public static final String DAILY = "DAILY";
    public static final String PURGE = "PURGE";
    public static final String BACKFILL = "BACKFILL";
    public static final String EXTERNAL = "EXTERNAL";

    public AuditAnchor(String service, long seq, String entryHash, String kind, Instant anchoredAt) {
        this(service, seq, entryHash, kind, anchoredAt, null);
    }

    public boolean isPurge() {
        return PURGE.equals(kind);
    }

    public boolean isExternal() {
        return EXTERNAL.equals(kind);
    }
}
