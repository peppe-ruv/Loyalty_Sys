package io.loyaltyhub.contestservice.wheel;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Ruote della fortuna (RF-95): elenco, probabilità teoriche per spicchio, giro. */
@RestController
@RequestMapping("/v1/wheels")
public class WheelController {
    public record SpinRequest(@NotBlank String memberId, String deviceFingerprint) {}
    private final WheelService service;
    private final WheelService.WheelSource wheels;
    public WheelController(WheelService service, WheelService.WheelSource wheels) { this.service = service; this.wheels = wheels; }

    @GetMapping public List<FortuneWheel> all() { return wheels.all(); }

    @GetMapping("/{id}/probabilities")
    public List<Map<String, Object>> probabilities(@PathVariable String id) {
        FortuneWheel w = wheels.byId(id).orElseThrow();
        var eligible = w.eligible(true);
        return w.slots().stream().map(s -> Map.<String, Object>of("slotId", s.id(), "label", s.label(), "probability", w.probabilityOf(s, w.mode() == FortuneWheel.Mode.PROBABILITY ? w.slots() : (s.winning() ? eligible : w.eligible(false))))).toList();
    }

    @PostMapping("/{id}/spins")
    public WheelService.Result spin(@PathVariable String id, @RequestBody SpinRequest r, @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        return service.spin(id, r.memberId(), r.deviceFingerprint(), ip);
    }

    @ExceptionHandler(WheelService.SpinRejected.class)
    public ResponseEntity<Map<String, String>> rejected(WheelService.SpinRejected e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage())); }
}
