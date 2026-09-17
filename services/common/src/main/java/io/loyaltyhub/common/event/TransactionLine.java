package io.loyaltyhub.common.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Riga di una transazione (RF-62). Per una utility una riga è un servizio o
 * prodotto acquistato (contratto, servizio a valore aggiunto, articolo del negozio), con etichette libere
 * (es. {@code segment=domestico}, {@code green=true}) su cui le regole e i segmenti possono filtrare.
 */
public record TransactionLine(
        String sku,
        String name,
        String category,
        String brand,
        BigDecimal quantity,
        BigDecimal amountEur,
        List<String> labels
) {
    public BigDecimal amountOrZero() { return amountEur == null ? BigDecimal.ZERO : amountEur; }

    public boolean hasLabel(String label) { return labels != null && labels.contains(label); }

    /** Converte l'attributo {@code lines} di un'azione (lista di mappe) in righe tipizzate; tollerante ai campi mancanti. */
    @SuppressWarnings("unchecked")
    public static List<TransactionLine> fromAttribute(Object lines) {
        if (!(lines instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).map(m -> new TransactionLine(
                str(m.get("sku")), str(m.get("name")), str(m.get("category")), str(m.get("brand")),
                dec(m.get("quantity")), dec(m.get("amountEur")),
                m.get("labels") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of())).toList();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal dec(Object o) { return o == null ? null : new BigDecimal(o.toString()); }
}
