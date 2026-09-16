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
    public PlayController(InstantWinService service) { this.service = service; }

    @PostMapping("/{contestId}/plays")
    public InstantWinService.Outcome play(@PathVariable UUID contestId, @RequestBody PlayRequest req,
                                          @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        return service.play(contestId, req.memberId(), req.deviceFingerprint(), ip);
    }

    @ExceptionHandler({InstantWinService.PlayLimitExceeded.class, InstantWinService.ContestNotOpen.class})
    public ResponseEntity<Map<String, String>> rejected(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
