package it.iren.loyalty.contestservice.api;

import it.iren.loyalty.contestservice.instantwin.InstantWinService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Giocata sincrona (RF-51): esito nella stessa richiesta. L'azione premiante CONTEST_PLAYED/WON è emessa via outbox (D08). */
@RestController
@RequestMapping("/v1/contests")
public class PlayController {
    public record PlayRequest(@NotBlank String memberId, String deviceFingerprint) {}

    private final InstantWinService service;
    private final it.iren.loyalty.contestservice.ContestEvents events;
    public PlayController(InstantWinService service, it.iren.loyalty.contestservice.ContestEvents events) { this.service = service; this.events = events; }

    @PostMapping("/{contestId}/plays")
    public InstantWinService.Outcome play(@PathVariable UUID contestId, @RequestBody PlayRequest req,
                                          @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        var out = service.play(contestId, req.memberId(), req.deviceFingerprint(), ip);
        events.played(contestId.toString(), req.memberId(), out.playId().toString(), out.won(), out.prizeCode(), out.playedAt());
        return out;
    }

    @ExceptionHandler({InstantWinService.PlayLimitExceeded.class, InstantWinService.ContestNotOpen.class})
    public ResponseEntity<Map<String, String>> rejected(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
