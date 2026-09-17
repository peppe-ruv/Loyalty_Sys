package it.iren.loyalty.identitymapping.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Identity resolution (RF-136): un cliente è uno solo anche se arriva da app, sito, POS, e-commerce o call center.
 * Il grafo collega identificatori di più tipi allo stesso membro; la risoluzione è deterministica (login, carta,
 * id CRM) e i collegamenti probabilistici (dispositivo condiviso) hanno confidenza &lt; 100 e non decidono da soli.
 * Merge e unmerge sono tracciati con snapshot per il ripristino; il membro assorbito resta un alias.
 */
public final class IdentityGraph {
    private IdentityGraph() {}

    public record Identifier(String kind, String value) {
        public Identifier { kind = kind == null ? null : kind.trim().toLowerCase(); value = value == null ? null : value.trim(); }
        public boolean valid() { return kind != null && !kind.isEmpty() && value != null && !value.isEmpty(); }
    }

    public record Link(String kind, String value, String memberId, String source, int confidence, Instant linkedAt, Instant lastSeen) {}

    /** Esito della risoluzione: membro trovato (o creato), identificatori nuovi collegati, conflitti (identificatori che puntano ad altri membri). */
    public record Resolution(String memberId, boolean created, List<Identifier> newlyLinked, Map<Identifier, String> conflicts) {
        public boolean ambiguous() { return memberId == null && !conflicts.isEmpty(); }
    }

    public record Merge(String mergeId, String fromMemberId, String intoMemberId, String reason, String actor, List<Link> movedLinks, Map<String, Long> unitsMoved, Instant mergedAt, Instant unmergedAt) {}

    /** Tipi con cui un identificatore da solo identifica il cliente (deterministici). */
    public static final List<String> DETERMINISTIC = List.of("oidc_sub", "crm_id", "sap_bp_id", "pos_card", "ecommerce_id", "email_hash", "phone_hash", "app_id");

    public static int defaultConfidence(String kind) { return DETERMINISTIC.contains(kind) ? 100 : 60; }
}
