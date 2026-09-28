package io.loyaltyhub.insight.domain;

/** Testa di una catena di audit ({@code audit_chain_head}): ultima {@code seq} accodata e il suo hash. */
public record AuditChainHead(String service, long seq, String entryHash) {
}
