package it.iren.loyalty.segmentservice.api;

import it.iren.loyalty.segmentservice.app.SegmentRecompute;
import it.iren.loyalty.segmentservice.domain.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API dei segmenti: appartenenze di un membro (per rules-engine, catalogo, CMS), membri di un segmento con export CSV
 * (RF-71), simulatore "questo membro entrerebbe nel segmento?" per il backoffice.
 */
@RestController
@RequestMapping("/v1/segments")
public class SegmentController {
    private final JdbcTemplate jdbc;
    private final SegmentSource segments;
    private final SnapshotSource snapshots;
    private final SegmentRecompute recompute;
    private final SegmentEvaluator evaluator = new SegmentEvaluator();

    public SegmentController(JdbcTemplate jdbc, SegmentSource segments, SnapshotSource snapshots, SegmentRecompute recompute) {
        this.jdbc = jdbc; this.segments = segments; this.snapshots = snapshots; this.recompute = recompute;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return segments.publishedSegments().stream().map(s -> Map.<String, Object>of("id", s.id(), "name", s.name(), "active", s.active(),
                "members", jdbc.queryForObject("SELECT count(*) FROM segmentservice.membership WHERE segment_id = ?", Long.class, s.id()))).toList();
    }

    @GetMapping("/members/{memberId}")
    public List<String> ofMember(@PathVariable String memberId) {
        return jdbc.queryForList("SELECT segment_id FROM segmentservice.membership WHERE member_id = ? ORDER BY segment_id", String.class, memberId);
    }

    @GetMapping("/{segmentId}/members")
    public List<Map<String, Object>> members(@PathVariable String segmentId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "500") int size) {
        return jdbc.queryForList("SELECT member_id, entered_at FROM segmentservice.membership WHERE segment_id = ? ORDER BY entered_at LIMIT ? OFFSET ?", segmentId, size, page * size);
    }

    /** Export per marketing/CRM (RF-71): solo id membro e data di ingresso, nessun dato anagrafico (D12). */
    @GetMapping(value = "/{segmentId}/members.csv", produces = "text/csv")
    public String exportCsv(@PathVariable String segmentId) {
        StringBuilder sb = new StringBuilder("member_id,entered_at\n");
        jdbc.query("SELECT member_id, entered_at FROM segmentservice.membership WHERE segment_id = ? ORDER BY entered_at", rs -> {
            sb.append(rs.getString(1)).append(',').append(rs.getTimestamp(2).toInstant()).append('\n');
        }, segmentId);
        return sb.toString();
    }

    @PostMapping("/members/{memberId}/recompute")
    public List<String> recomputeMember(@PathVariable String memberId) {
        recompute.recomputeMember(memberId);
        return ofMember(memberId);
    }

    /** Simulatore per il backoffice: valuta una definizione (anche non pubblicata) su un membro reale. */
    @PostMapping(value = "/simulate/{memberId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> simulate(@PathVariable String memberId, @RequestBody Segment definition) {
        var snap = snapshots.snapshotOf(memberId).orElse(null);
        boolean in = snap != null && evaluator.matches(definition, snap, Instant.now());
        return Map.of("memberId", memberId, "segmentId", definition.id(), "matches", in);
    }
}
