package io.loyaltyhub.insight.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forma canonica e hash della catena di audit, versione 1 (F2-GRC-07; docs/servizi/insight-service.md §5). I vettori
 * attesi sono calcolati fuori dal codice (SHA-256 della forma scritta a mano): un cambio involontario della forma
 * canonica rompe questi test prima di rompere le catene già scritte. La concordanza con le funzioni del database è
 * provata da {@code AuditChainIT} e {@code AuditChainMigrationIT}.
 */
class AuditHashChainTest {

    private static final Instant AT = Instant.parse("2026-09-28T10:15:30.123456Z");

    private static AuditChainLink link(String contentHash) {
        return new AuditChainLink("campaign", 1, AuditHashChain.GENESIS, "01J00000000000000000000000", "EVT-1", AT,
                "ADMIN", "marta.admin", "CAMPAIGN", "CMP-1", "UPDATE", null, null, null, null, contentHash, null, null);
    }

    @Test
    @DisplayName("netstring: byte UTF-8 in decimale, due punti, valore, virgola; NULL è ~")
    void netstring() {
        assertThat(AuditHashChain.netstring(null)).isEqualTo("~");
        assertThat(AuditHashChain.netstring("")).isEqualTo("0:,");
        assertThat(AuditHashChain.netstring("abc")).isEqualTo("3:abc,");
        assertThat(AuditHashChain.netstring("«è»")).as("lunghezza in byte, non in caratteri").isEqualTo("6:«è»,");
        assertThat(AuditHashChain.netstring("😀")).as("fuori dal piano base: 4 byte").isEqualTo("4:😀,");
    }

    @Test
    @DisplayName("istante: UTC con sei cifre di microsecondi, anche se zero")
    void instant() {
        assertThat(AuditHashChain.formatAt(AT)).isEqualTo("2026-09-28T10:15:30.123456Z");
        assertThat(AuditHashChain.formatAt(Instant.parse("2035-10-01T10:00:00Z"))).isEqualTo("2035-10-01T10:00:00.000000Z");
        assertThat(AuditHashChain.formatAt(null)).isNull();
    }

    @Test
    @DisplayName("genesi: 64 zeri")
    void genesis() {
        assertThat(AuditHashChain.GENESIS).hasSize(64).matches("0+");
    }

    @Test
    @DisplayName("hash del contenuto: vettore di riferimento")
    void contentVector() {
        assertThat(AuditHashChain.canonicalContent("Modificata campagna «Estate»", "{\"name\": \"Vecchio\"}", null))
                .isEqualTo("19:lh.audit.content.v1,30:Modificata campagna «Estate»,19:{\"name\": \"Vecchio\"},~");
        assertThat(AuditHashChain.contentHash("Modificata campagna «Estate»", "{\"name\": \"Vecchio\"}", null))
                .isEqualTo("b7ecdb81b4e4fa2519d73e610fb007f787db423ffe7fc92653fd5be71165cb36");
    }

    @Test
    @DisplayName("hash della voce: vettore di riferimento (campo NULL come ~, contenuto per hash)")
    void entryVector() {
        AuditChainLink l = link("b7ecdb81b4e4fa2519d73e610fb007f787db423ffe7fc92653fd5be71165cb36");
        assertThat(AuditHashChain.canonicalEntry(l)).isEqualTo("17:lh.audit.entry.v1,8:campaign,1:1,64:" + "0".repeat(64)
                + ",26:01J00000000000000000000000,5:EVT-1,27:2026-09-28T10:15:30.123456Z,5:ADMIN,11:marta.admin,"
                + "8:CAMPAIGN,5:CMP-1,6:UPDATE,~64:b7ecdb81b4e4fa2519d73e610fb007f787db423ffe7fc92653fd5be71165cb36,");
        assertThat(AuditHashChain.entryHash(l)).isEqualTo("40566426baa47f3e854b392b0d318ae9480da535c8d188eb6a8d4f21e6a10ad2");
    }

    @Test
    @DisplayName("NULL e stringa vuota danno hash diversi")
    void nullIsNotEmpty() {
        assertThat(AuditHashChain.contentHash(null, null, null)).isNotEqualTo(AuditHashChain.contentHash("", null, null));
        assertThat(AuditHashChain.contentHash(null, "", null)).isNotEqualTo(AuditHashChain.contentHash(null, null, ""));
    }

    @Test
    @DisplayName("spostare testo da un campo all'altro cambia l'hash (i confini non si falsificano)")
    void boundariesAreBound() {
        // Con una concatenazione ingenua "a,b" + "c" e "a" + "b,c" coinciderebbero.
        assertThat(AuditHashChain.contentHash("a,1:b", "c", null))
                .isNotEqualTo(AuditHashChain.contentHash("a", "b,1:c", null));
        assertThat(AuditHashChain.contentHash("x", null, "y"))
                .isNotEqualTo(AuditHashChain.contentHash("x", "y", null));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"service", "seq", "prevHash", "id", "eventId", "at", "actorRole", "actorName", "entityType",
            "entityId", "action", "correlationId", "contentHash"})
    @DisplayName("ogni campo della voce entra nell'hash")
    void everyFieldCounts(String field) {
        AuditChainLink base = link("c".repeat(64));
        AuditChainLink changed = switch (field) {
            case "service" -> with(base, "service");
            case "seq" -> new AuditChainLink(base.service(), 2, base.prevHash(), base.id(), base.eventId(), base.at(),
                    base.actorRole(), base.actorName(), base.entityType(), base.entityId(), base.action(),
                    base.correlationId(), null, null, null, base.contentHash(), null, null);
            case "at" -> new AuditChainLink(base.service(), base.seq(), base.prevHash(), base.id(), base.eventId(),
                    base.at().plusNanos(1_000), base.actorRole(), base.actorName(), base.entityType(), base.entityId(),
                    base.action(), base.correlationId(), null, null, null, base.contentHash(), null, null);
            default -> with(base, field);
        };
        assertThat(AuditHashChain.entryHash(changed)).isNotEqualTo(AuditHashChain.entryHash(base));
    }

    @Test
    @DisplayName("il contenuto entra solo attraverso il suo hash; redacted_at e l'hash memorizzato no")
    void contentOnlyThroughItsHash() {
        AuditChainLink base = link("c".repeat(64));
        AuditChainLink rewritten = new AuditChainLink(base.service(), base.seq(), base.prevHash(), base.id(),
                base.eventId(), base.at(), base.actorRole(), base.actorName(), base.entityType(), base.entityId(),
                base.action(), base.correlationId(), "Membro anonimo", "{}", "{}", base.contentHash(), "x",
                Instant.now());
        assertThat(AuditHashChain.entryHash(rewritten)).isEqualTo(AuditHashChain.entryHash(base));
    }

    /** Copia di {@code l} con il campo testuale indicato cambiato ({@code null} diventa un valore, un valore cambia). */
    private static AuditChainLink with(AuditChainLink l, String field) {
        String v = "cambiato";
        return new AuditChainLink(
                field.equals("service") ? v : l.service(), l.seq(),
                field.equals("prevHash") ? "f".repeat(64) : l.prevHash(),
                field.equals("id") ? v : l.id(),
                field.equals("eventId") ? v : l.eventId(), l.at(),
                field.equals("actorRole") ? v : l.actorRole(),
                field.equals("actorName") ? v : l.actorName(),
                field.equals("entityType") ? v : l.entityType(),
                field.equals("entityId") ? v : l.entityId(),
                field.equals("action") ? v : l.action(),
                field.equals("correlationId") ? v : l.correlationId(),
                l.summary(), l.beforeJson(), l.afterJson(),
                field.equals("contentHash") ? "d".repeat(64) : l.contentHash(),
                l.entryHash(), l.redactedAt());
    }
}
