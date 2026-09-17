package io.loyaltyhub.ingressadapters.catalog;

import io.loyaltyhub.common.event.TransactionLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Porta verso il catalogo (RF-101) e arricchimento delle righe: i campi presenti nella riga prevalgono su quelli del catalogo. */
public interface ProductCatalog {
    Optional<Product> bySku(String sku);

    default List<TransactionLine> enrich(List<TransactionLine> lines) {
        List<TransactionLine> out = new ArrayList<>(lines.size());
        for (TransactionLine l : lines) {
            Product p = l.sku() == null ? null : bySku(l.sku()).orElse(null);
            if (p == null) { out.add(l); continue; }
            List<String> labels = new ArrayList<>(l.labels() == null ? List.of() : l.labels());
            if (p.labels() != null) for (String x : p.labels()) if (!labels.contains(x)) labels.add(x);
            out.add(new TransactionLine(l.sku(), l.name() != null ? l.name() : p.name(), l.category() != null ? l.category() : p.category(),
                    l.brand() != null ? l.brand() : p.brand(), l.quantity(), l.amountEur() != null ? l.amountEur() : p.price() == null ? null : p.price().multiply(l.quantity() == null ? java.math.BigDecimal.ONE : l.quantity()), labels));
        }
        return out;
    }

    /** Import CSV: {@code sku,name,category,brand,price,labels(;),attr=value(;)}. */
    static Product parseCsvLine(String line) {
        String[] f = line.split(",", -1);
        if (f.length < 1 || f[0].isBlank()) return null;
        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        if (f.length > 6) for (String kv : f[6].split(";")) if (kv.contains("=")) attrs.put(kv.split("=")[0].trim(), kv.split("=")[1].trim());
        return new Product(f[0].trim(), f.length > 1 ? f[1].trim() : null, f.length > 2 && !f[2].isBlank() ? f[2].trim() : null, f.length > 3 && !f[3].isBlank() ? f[3].trim() : null,
                f.length > 4 && !f[4].isBlank() ? new java.math.BigDecimal(f[4].trim()) : null,
                f.length > 5 && !f[5].isBlank() ? List.of(f[5].split(";")) : List.of(), attrs, true);
    }
}
