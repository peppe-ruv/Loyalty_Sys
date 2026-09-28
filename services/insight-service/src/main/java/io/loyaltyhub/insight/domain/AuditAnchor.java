package io.loyaltyhub.insight.domain;

import java.time.Instant;

/**
 * Un'ancora della catena di audit: l'hash della voce {@code seq} del servizio, registrato a parte. Nel database
 * ({@code audit_anchor}, sola inserzione): {@code DAILY} = job giornaliero dopo una verifica; {@code PURGE} = ultima voce
 * cancellata dalla retention, a cui la prima voce rimasta si aggancia; {@code BACKFILL} = stato delle voci esistenti alla
 * migrazione V6. {@code EXTERNAL} non è nel database: è un'ancora copiata dai log e passata alla verifica
 * ({@code GET /v1/audit/verify?service=&seq=&hash=}).
 */
public record AuditAnchor(String service, long seq, String entryHash, String kind, Instant anchoredAt) {

    public static final String DAILY = "DAILY";
    public static final String PURGE = "PURGE";
    public static final String BACKFILL = "BACKFILL";
    public static final String EXTERNAL = "EXTERNAL";

    public boolean isPurge() {
        return PURGE.equals(kind);
    }

    public boolean isExternal() {
        return EXTERNAL.equals(kind);
    }
}
