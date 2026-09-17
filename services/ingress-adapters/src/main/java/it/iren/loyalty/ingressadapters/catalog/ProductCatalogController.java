package it.iren.loyalty.ingressadapters.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Catalogo prodotti (RF-101): import CSV, ricerca per SKU/categoria/marca, dizionario categorie. */
@RestController
@RequestMapping("/v1/catalog")
public class ProductCatalogController {
    private final JdbcTemplate jdbc;
    private final ProductCatalog catalog;
    public ProductCatalogController(JdbcTemplate jdbc, ProductCatalog catalog) { this.jdbc = jdbc; this.catalog = catalog; }

    @PostMapping(value = "/products/import", consumes = "text/csv")
    public Map<String, Integer> importCsv(@RequestBody String csv) {
        int n = 0;
        for (String line : csv.split("\r?\n")) {
            if (line.isBlank() || line.startsWith("sku,")) continue;
            Product p = ProductCatalog.parseCsvLine(line);
            if (p == null) continue;
            jdbc.update("INSERT INTO ingressadapters.product(sku, name, category, brand, price, labels, attributes, active, updated_at) VALUES (?,?,?,?,?,?,?::jsonb,true,now()) ON CONFLICT (sku) DO UPDATE SET name=EXCLUDED.name, category=EXCLUDED.category, brand=EXCLUDED.brand, price=EXCLUDED.price, labels=EXCLUDED.labels, attributes=EXCLUDED.attributes, active=true, updated_at=now()",
                    p.sku(), p.name(), p.category(), p.brand(), p.price(), String.join(";", p.labels()), toJson(p.attributes()));
            n++;
        }
        return Map.of("imported", n);
    }

    @GetMapping("/products/{sku}")
    public ResponseEntity<Product> get(@PathVariable String sku) { return ResponseEntity.of(catalog.bySku(sku)); }

    @GetMapping("/products")
    public List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(required = false) String category, @RequestParam(required = false) String brand, @RequestParam(defaultValue = "100") int size) {
        return jdbc.queryForList("SELECT sku, name, category, brand, price, labels FROM ingressadapters.product WHERE active AND (? IS NULL OR sku ILIKE '%'||?||'%' OR name ILIKE '%'||?||'%') AND (? IS NULL OR category = ?) AND (? IS NULL OR brand = ?) ORDER BY sku LIMIT ?",
                q, q, q, category, category, brand, brand, size);
    }

    @GetMapping("/categories")
    public List<String> categories() { return jdbc.queryForList("SELECT DISTINCT category FROM ingressadapters.product WHERE category IS NOT NULL ORDER BY 1", String.class); }

    private static String toJson(Map<String, String> m) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }
}
