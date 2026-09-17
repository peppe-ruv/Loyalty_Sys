package it.iren.loyalty.memberservice.api;

import it.iren.loyalty.memberservice.domain.CustomFieldSchema;
import it.iren.loyalty.memberservice.domain.CustomFieldSource;
import it.iren.loyalty.memberservice.domain.MemberIdentifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/** Campi custom del membro (RF-99): schemi, lettura e scrittura validata per gruppo; identificatori e tessera (RF-108). */
@RestController
@RequestMapping("/v1/members")
public class CustomFieldController {
    private final JdbcTemplate jdbc;
    private final CustomFieldSource source;
    private final RestClient segments;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final SecureRandom rnd = new SecureRandom();

    public CustomFieldController(JdbcTemplate jdbc, CustomFieldSource source, RestClient.Builder b) {
        this.jdbc = jdbc; this.source = source;
        this.segments = b.baseUrl(System.getenv().getOrDefault("SEGMENT_URL", "http://segment-service:8090")).build();
    }

    @GetMapping("/custom-fields/schemas")
    public List<CustomFieldSchema> schemas() { return source.schemasFor(CustomFieldSchema.Entity.MEMBER); }

    @GetMapping("/{id}/custom-fields")
    public Map<String, Object> get(@PathVariable String id) {
        var rows = jdbc.queryForList("SELECT group_name, row_index, values::text FROM memberservice.custom_field WHERE member_id = ? ORDER BY group_name, row_index", id);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (var r : rows) {
            try {
                Object v = json.readValue((String) r.get("values"), Map.class);
                if (((Number) r.get("row_index")).intValue() >= 0) ((List<Object>) out.computeIfAbsent((String) r.get("group_name"), k -> new java.util.ArrayList<>())).add(v);
                else out.put((String) r.get("group_name"), v);
            } catch (Exception ignored) {}
        }
        return out;
    }

    @PutMapping("/{id}/custom-fields/{group}")
    public Map<String, Object> put(@PathVariable String id, @PathVariable String group, @RequestBody Object body) {
        CustomFieldSchema schema = source.schemasFor(CustomFieldSchema.Entity.MEMBER).stream().filter(s -> s.group().equals(group)).findFirst().orElseThrow(() -> new IllegalArgumentException("unknown group " + group));
        List<Map<String, Object>> rows = schema.repeatable() && body instanceof List<?> l ? l.stream().map(o -> (Map<String, Object>) o).toList() : List.of((Map<String, Object>) body);
        java.util.function.BiPredicate<String, String> inCollection = (c, v) -> { try { return Boolean.TRUE.equals(segments.get().uri("/v1/collections/{c}/contains?value={v}", c, v).retrieve().body(Boolean.class)); } catch (Exception e) { return false; } };
        List<String> errors = new java.util.ArrayList<>();
        for (var r : rows) errors.addAll(schema.validate(r, inCollection));
        if (!errors.isEmpty()) return Map.of("saved", false, "errors", errors);
        jdbc.update("DELETE FROM memberservice.custom_field WHERE member_id = ? AND group_name = ?", id, group);
        for (int i = 0; i < rows.size(); i++) {
            try { jdbc.update("INSERT INTO memberservice.custom_field(member_id, group_name, row_index, values) VALUES (?,?,?,?::jsonb)", id, group, schema.repeatable() ? i : -1, json.writeValueAsString(rows.get(i))); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        return Map.of("saved", true, "rows", rows.size());
    }

    @GetMapping("/identifiers/config")
    public MemberIdentifiers identifiers() { return source.identifiers(); }

    /** Tessera fedeltà generata (RF-108), unica per tenant. */
    @PostMapping("/{id}/loyalty-card")
    public Map<String, String> issueCard(@PathVariable String id) {
        var gen = source.identifiers().card();
        if (!gen.enabled()) throw new IllegalStateException("card generation disabled");
        for (int attempt = 0; attempt < 10; attempt++) {
            String card = gen.generate(rnd);
            if (jdbc.update("INSERT INTO memberservice.member_identifier(member_id, kind, value) VALUES (?,'LOYALTY_CARD',?) ON CONFLICT DO NOTHING", id, card) == 1) return Map.of("memberId", id, "loyaltyCard", card);
        }
        throw new IllegalStateException("could not generate a unique card");
    }

    @PutMapping("/{id}/identifiers/{kind}")
    public Map<String, String> setIdentifier(@PathVariable String id, @PathVariable String kind, @RequestBody Map<String, String> body) {
        MemberIdentifiers.Kind k = MemberIdentifiers.Kind.valueOf(kind.toUpperCase());
        var cfg = source.identifiers().priority().stream().filter(i -> i.kind() == k).findFirst().orElseThrow();
        String value = body.get("value").trim();
        if (cfg.unique()) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM memberservice.member_identifier WHERE kind = ? AND value = ? AND member_id <> ?", Integer.class, k.name(), value, id);
            if (n != null && n > 0) return Map.of("saved", "false", "error", "NOT_UNIQUE");
        }
        jdbc.update("INSERT INTO memberservice.member_identifier(member_id, kind, value) VALUES (?,?,?) ON CONFLICT (member_id, kind) DO UPDATE SET value = EXCLUDED.value", id, k.name(), value);
        return Map.of("saved", "true");
    }

    /** Risoluzione di un evento (RI-02): dato un insieme di identificatori noti, restituisce il membro secondo la priorità configurata. */
    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestBody Map<String, String> known) {
        Map<MemberIdentifiers.Kind, String> k = new java.util.EnumMap<>(MemberIdentifiers.Kind.class);
        known.forEach((key, v) -> { try { k.put(MemberIdentifiers.Kind.valueOf(key.toUpperCase()), v); } catch (IllegalArgumentException ignored) {} });
        for (var id : source.identifiers().priority()) {
            if (!id.matching() || k.get(id.kind()) == null) continue;
            var rows = jdbc.queryForList("SELECT member_id FROM memberservice.member_identifier WHERE kind = ? AND value = ?", id.kind().name(), k.get(id.kind()));
            if (!rows.isEmpty()) return Map.of("memberId", rows.get(0).get("member_id"), "matchedBy", id.kind().name());
        }
        return Map.of("memberId", "", "matchedBy", "");
    }
}
