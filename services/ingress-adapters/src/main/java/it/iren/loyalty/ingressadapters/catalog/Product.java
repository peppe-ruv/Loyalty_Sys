package it.iren.loyalty.ingressadapters.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Prodotto del catalogo (RF-101): copia in sola lettura dei prodotti/servizi (SKU, nome, categoria, marca, prezzo,
 * etichette, attributi custom) importata da CSV dal sistema sorgente (PIM, listino offerte, negozio). Serve ad
 * arricchire le righe delle transazioni all'ingresso: una fonte che manda solo lo SKU ottiene categoria, marca, etichette.
 */
public record Product(String sku, String name, String category, String brand, BigDecimal price, List<String> labels, Map<String, String> attributes, boolean active) {}
