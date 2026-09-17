package io.loyaltyhub.notifier.api;

import io.loyaltyhub.notifier.delivery.DeliveryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Inbox in app / offerte per te (canale APP e WEB, RF-132) e coda operatore. Il BFF espone l'inbox all'area riservata;
 * lettura e accettazione generano le azioni che alimentano previsioni e campagne (OFFER_ACCEPTED).
 */
@RestController
@RequestMapping("/v1")
public class InboxController {
    private final JdbcTemplate jdbc;
    private final DeliveryService delivery;
    public InboxController(JdbcTemplate jdbc, DeliveryService delivery) { this.jdbc = jdbc; this.delivery = delivery; }

    @GetMapping("/inbox/{memberId}")
    public List<Map<String, Object>> inbox(@PathVariable String memberId, @RequestParam(defaultValue = "false") boolean includeRead) {
        return jdbc.queryForList("SELECT id, decision_id, action, reference, subject, body, params::text AS params, status, created_at, expires_at FROM notifier.inbox_message WHERE member_id = ? AND (expires_at IS NULL OR expires_at > now()) " +
                (includeRead ? "" : "AND status IN ('NEW','READ') ") + "ORDER BY created_at DESC LIMIT 50", memberId);
    }

    @PostMapping("/inbox/{memberId}/{id}/read")
    public Map<String, Object> read(@PathVariable String memberId, @PathVariable String id) {
        jdbc.update("UPDATE notifier.inbox_message SET status = 'READ', read_at = now() WHERE id = ? AND member_id = ? AND status = 'NEW'", id, memberId);
        return Map.of("status", "READ");
    }

    @PostMapping("/inbox/{memberId}/{id}/accept")
    public Map<String, Object> accept(@PathVariable String memberId, @PathVariable String id) {
        var rows = jdbc.queryForList("SELECT decision_id, reference FROM notifier.inbox_message WHERE id = ? AND member_id = ? AND status IN ('NEW','READ')", id, memberId);
        if (rows.isEmpty()) return Map.of("status", "NOT_FOUND");
        jdbc.update("UPDATE notifier.inbox_message SET status = 'ACCEPTED', acted_at = now() WHERE id = ?", id);
        delivery.accepted(memberId, String.valueOf(rows.get(0).get("decision_id")), String.valueOf(rows.get(0).get("reference")), "app");
        return Map.of("status", "ACCEPTED");
    }

    @PostMapping("/inbox/{memberId}/{id}/dismiss")
    public Map<String, Object> dismiss(@PathVariable String memberId, @PathVariable String id) {
        jdbc.update("UPDATE notifier.inbox_message SET status = 'DISMISSED', acted_at = now() WHERE id = ? AND member_id = ?", id, memberId);
        return Map.of("status", "DISMISSED");
    }

    @GetMapping("/operator-queue")
    public List<Map<String, Object>> queue(@RequestParam(defaultValue = "OPEN") String status) {
        return jdbc.queryForList("SELECT id, member_id, decision_id, action, reference, note, status, created_at FROM notifier.operator_queue WHERE status = ? ORDER BY created_at LIMIT 200", status);
    }

    @PostMapping("/operator-queue/{id}/handle")
    public Map<String, Object> handle(@PathVariable String id, @RequestBody Map<String, String> body) {
        jdbc.update("UPDATE notifier.operator_queue SET status = ?, handled_by = ?, handled_at = now() WHERE id = ?", body.getOrDefault("status", "DONE"), body.get("operator"), id);
        return Map.of("status", body.getOrDefault("status", "DONE"));
    }

    @GetMapping("/deliveries/{memberId}")
    public List<Map<String, Object>> deliveries(@PathVariable String memberId) {
        return jdbc.queryForList("SELECT delivery_id, decision_id, action, reference, channel, status, detail, created_at FROM notifier.delivery_log WHERE member_id = ? ORDER BY created_at DESC LIMIT 100", memberId);
    }
}
