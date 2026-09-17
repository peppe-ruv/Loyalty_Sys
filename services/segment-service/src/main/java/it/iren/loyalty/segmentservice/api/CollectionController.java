package it.iren.loyalty.segmentservice.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Collezioni di valori (RF-100): liste riutilizzabili (SKU, CAP, comuni, email...) fino a 1.000.000 di valori, caricate
 * da CSV o una alla volta; usate nelle condizioni di campagne e segmenti ({@code #fn.in_collection('nome', valore)}).
 */
@RestController
@RequestMapping("/v1/collections")
public class CollectionController {
    private final JdbcTemplate jdbc;
    public CollectionController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping public List<Map<String, Object>> list() { return jdbc.queryForList("SELECT collection_id, count(*) AS values FROM segmentservice.collection_value GROUP BY collection_id ORDER BY 1"); }

    @GetMapping("/{id}/contains")
    public boolean contains(@PathVariable String id, @RequestParam String value) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM segmentservice.collection_value WHERE collection_id = ? AND value = ?", Integer.class, id, value.trim());
        return n != null && n > 0;
    }

    @PostMapping(value = "/{id}/values", consumes = "text/csv")
    public Map<String, Object> load(@PathVariable String id, @RequestBody String csv, @RequestParam(defaultValue = "false") boolean replace) {
        if (replace) jdbc.update("DELETE FROM segmentservice.collection_value WHERE collection_id = ?", id);
        int n = 0;
        for (String v : Arrays.asList(csv.split("\r?\n"))) if (!v.isBlank()) n += jdbc.update("INSERT INTO segmentservice.collection_value(collection_id, value) VALUES (?,?) ON CONFLICT DO NOTHING", id, v.trim().split(",")[0].trim());
        return Map.of("collectionId", id, "loaded", n);
    }

    @PostMapping("/{id}/values/{value}")
    public Map<String, Object> add(@PathVariable String id, @PathVariable String value) { jdbc.update("INSERT INTO segmentservice.collection_value(collection_id, value) VALUES (?,?) ON CONFLICT DO NOTHING", id, value); return Map.of("ok", true); }

    @DeleteMapping("/{id}/values/{value}")
    public Map<String, Object> remove(@PathVariable String id, @PathVariable String value) { jdbc.update("DELETE FROM segmentservice.collection_value WHERE collection_id = ? AND value = ?", id, value); return Map.of("ok", true); }
}
