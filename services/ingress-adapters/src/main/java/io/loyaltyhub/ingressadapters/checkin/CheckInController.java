package io.loyaltyhub.ingressadapters.checkin;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.ingressadapters.dedup.DedupService;
import io.loyaltyhub.ingressadapters.publish.ActionPublisher;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * Check-in geolocalizzato (RF-67): l'app invia posizione e luogo dichiarato; l'azione CHECK_IN porta lat/lon e la regola
 * con operatore GEO_WITHIN decide se premiare. Una chiave per membro, luogo e giorno: un solo check-in valido al giorno per luogo.
 */
@RestController
@RequestMapping("/v1/check-ins")
public class CheckInController {
    public record CheckIn(@NotBlank String memberId, @NotBlank String placeId, double lat, double lon, String channel) {}

    private final DedupService dedup;
    private final ActionPublisher publisher;
    public CheckInController(DedupService dedup, ActionPublisher publisher) { this.dedup = dedup; this.publisher = publisher; }

    @PostMapping
    public ResponseEntity<Map<String, String>> checkIn(@RequestBody CheckIn c) {
        Instant now = Instant.now();
        String day = now.atZone(ZoneId.of("Europe/Rome")).toLocalDate().toString();
        String key = "checkin:" + c.memberId() + ":" + c.placeId() + ":" + day;
        if (!dedup.firstSeen(key)) return ResponseEntity.ok(Map.of("status", "DUPLICATE"));
        var action = new RewardingAction(EventTypes.ACTION_CHECK_IN, key, c.placeId(), now, null,
                Map.of(EventTypes.ATTR_LAT, c.lat(), EventTypes.ATTR_LON, c.lon(), "placeId", c.placeId(), EventTypes.ATTR_CHANNEL, c.channel() == null ? "app" : c.channel()));
        try {
            publisher.publish(CanonicalEvents.action("urn:loyaltyhub:source:app", c.memberId(), action));
        } catch (ActionPublisher.PublishFailed e) {
            // Chiave rilasciata: il check-in non è entrato e l'app deve poter riprovare (RI-01).
            dedup.forget(key);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "FAILED", "reason", "BROKER_UNAVAILABLE"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "ACCEPTED"));
    }
}
