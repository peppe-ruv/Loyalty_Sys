package io.loyaltyhub.insight.domain;

import java.time.Instant;

/**
 * Un'ancora della catena di audit ({@code audit_anchor}, sola inserzione): l'hash della voce {@code seq} del servizio,
 * registrato a parte. {@code DAILY} = job giornaliero dopo una verifica; {@code PURGE} = ultima voce cancellata dalla
 * retention; {@code BACKFILL} = stato delle voci esistenti alla migrazione V6.
 */
public record AuditAnchor(String service, long seq, String entryHash, String kind, Instant anchoredAt) {

    public static final String DAILY = "DAILY";
    public static final String PURGE = "PURGE";
    public static final String BACKFILL = "BACKFILL";
}
