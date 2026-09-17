package it.iren.loyalty.segmentservice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Vista del membro usata dai criteri: profilo (adesione, tier, etichette, consensi, saldo) e riassunto delle azioni.
 * È costruita dal read-model (CQRS, D10); il valutatore non interroga altri servizi.
 */
public record MemberSnapshot(
        String memberId,
        Instant enrolledAt,
        String tier,
        Map<String, String> labels,
        Map<String, Boolean> consents,
        long premioAvailable,
        List<ActionSummary> actions
) {
    /** Riassunto di un'azione premiante: tipo, quando, importo, canale e righe (RF-62). */
    public record ActionSummary(String actionType, Instant occurredAt, BigDecimal amountEur, String channel, List<Line> lines) {
        public BigDecimal amountOrZero() { return amountEur == null ? BigDecimal.ZERO : amountEur; }
    }
    public record Line(String sku, String brand, String category, List<String> labels) {}
}
